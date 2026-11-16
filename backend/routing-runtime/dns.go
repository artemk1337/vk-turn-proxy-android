package routingruntime

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func (r *router) observeDNS(query, response []byte, now time.Time) error {
	var request, answer dnsmessage.Message
	if request.Unpack(query) != nil || answer.Unpack(response) != nil || !answer.Response || request.ID != answer.ID || len(request.Questions) != len(answer.Questions) {
		return errors.New("invalid DNS response")
	}
	selected := make(map[string]time.Time)
	unlimited := now.Add((1 << 32) * time.Second)
	for i, question := range request.Questions {
		if question != answer.Questions[i] {
			return errors.New("DNS question mismatch")
		}
		name := strings.ToLower(question.Name.String())
		if r.matcher.MatchesDomain(name) {
			selected[name] = unlimited
		}
	}
	records := append(append(answer.Answers, answer.Authorities...), answer.Additionals...)
	for _, record := range records {
		name := strings.ToLower(record.Header.Name.String())
		if r.matcher.MatchesDomain(name) {
			selected[name] = unlimited
		}
	}
	// Selected aliases inherit the shortest TTL along their dependency chain.
	for range len(records) + 1 {
		changed := false
		for _, record := range records {
			owner := strings.ToLower(record.Header.Name.String())
			var target string
			service := false
			switch body := record.Body.(type) {
			case *dnsmessage.CNAMEResource:
				target = strings.ToLower(body.CNAME.String())
			case *dnsmessage.SVCBResource:
				target = strings.ToLower(body.Target.String())
				service = true
			case *dnsmessage.HTTPSResource:
				target = strings.ToLower(body.Target.String())
				service = true
			}
			if target == "" || target == "." {
				continue
			}
			if r.matcher.MatchesDomain(target) {
				selected[target] = unlimited
				if service {
					selected[owner] = unlimited
				}
			}
			if expiry, ok := selected[owner]; ok {
				limit := now.Add(time.Duration(max(record.Header.TTL, 1)) * time.Second)
				if expiry.Before(limit) {
					limit = expiry
				}
				if limit.After(selected[target]) {
					selected[target] = limit
					changed = true
				}
			}
		}
		if !changed {
			break
		}
	}
	addresses := make(map[netip.Addr]time.Time)
	for _, record := range records {
		owner := strings.ToLower(record.Header.Name.String())
		if _, ok := selected[owner]; !ok {
			continue
		}
		ttl := max(record.Header.TTL, 1)
		expires := now.Add(time.Duration(ttl) * time.Second)
		if selected[owner].Before(expires) {
			expires = selected[owner]
		}
		switch body := record.Body.(type) {
		case *dnsmessage.AResource:
			addresses[netip.AddrFrom4(body.A)] = expires
		case *dnsmessage.SVCBResource:
			if err := addHints(addresses, *body, expires); err != nil {
				return err
			}
		case *dnsmessage.HTTPSResource:
			if err := addHints(addresses, body.SVCBResource, expires); err != nil {
				return err
			}
		}
	}
	return r.learn(addresses, now)
}

func addHints(addresses map[netip.Addr]time.Time, binding dnsmessage.SVCBResource, expires time.Time) error {
	if binding.Priority == 0 {
		return nil
	}
	for i, param := range binding.Params {
		if i > 0 && param.Key <= binding.Params[i-1].Key {
			return errors.New("invalid service binding parameters")
		}
		if param.Key != dnsmessage.SVCParamIPv4Hint {
			continue
		}
		if len(param.Value) == 0 || len(param.Value)%4 != 0 {
			return errors.New("invalid IPv4 service binding hints")
		}
		for offset := 0; offset < len(param.Value); offset += 4 {
			addresses[netip.AddrFrom4([4]byte(param.Value[offset:offset+4]))] = expires
		}
	}
	return nil
}

func (r *Runtime) dnsQuery(network, destination string, query []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(r.ctx, 15*time.Second)
	defer cancel()
	conn, err := r.tunnel.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	if !r.track(conn) {
		conn.Close()
		return nil, net.ErrClosed
	}
	defer r.release(conn)
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	if network == "tcp" {
		if err := writeDNSFrame(conn, query); err != nil {
			return nil, err
		}
		return readDNSFrame(conn)
	}
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	response := make([]byte, 65535)
	n, err := conn.Read(response)
	if err != nil {
		return nil, err
	}
	var header dnsmessage.Parser
	h, err := header.Start(response[:n])
	if err != nil {
		return nil, err
	}
	if h.Truncated {
		return r.dnsQuery("tcp", destination, query)
	}
	return response[:n], nil
}

func readDNSFrame(conn net.Conn) ([]byte, error) {
	var length [2]byte
	if _, err := io.ReadFull(conn, length[:]); err != nil {
		return nil, err
	}
	size := int(binary.BigEndian.Uint16(length[:]))
	if size < 12 {
		return nil, errors.New("invalid DNS frame")
	}
	message := make([]byte, size)
	_, err := io.ReadFull(conn, message)
	return message, err
}

func writeDNSFrame(conn net.Conn, message []byte) error {
	frame := make([]byte, 2+len(message))
	binary.BigEndian.PutUint16(frame, uint16(len(message)))
	copy(frame[2:], message)
	for len(frame) > 0 {
		n, err := conn.Write(frame)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		frame = frame[n:]
	}
	return nil
}

func (r *Runtime) serveDNS(conn net.Conn, network, destination string) {
	buffer := make([]byte, 65535)
	for {
		conn.SetReadDeadline(time.Now().Add(2 * time.Minute))
		var query []byte
		var err error
		if network == "tcp" {
			query, err = readDNSFrame(conn)
		} else {
			var n int
			n, err = conn.Read(buffer)
			query = buffer[:n]
		}
		if err != nil {
			return
		}
		response, err := r.dnsQuery(network, destination, query)
		if err == nil {
			err = r.router.observeDNS(query, response, time.Now())
		}
		if err != nil {
			response = dnsFailure(query)
		}
		if len(response) == 0 {
			continue
		}
		if network == "tcp" {
			err = writeDNSFrame(conn, response)
		} else {
			_, err = conn.Write(response)
		}
		if err != nil {
			return
		}
	}
}

func dnsFailure(query []byte) []byte {
	var message dnsmessage.Message
	if message.Unpack(query) != nil {
		return nil
	}
	message.Response = true
	message.RCode = dnsmessage.RCodeServerFailure
	message.Answers, message.Authorities, message.Additionals = nil, nil, nil
	result, _ := message.Pack()
	return result
}
