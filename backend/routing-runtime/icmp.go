package routingruntime

import (
	"encoding/binary"
	"net"
	"net/netip"
	"time"

	"golang.org/x/net/icmp"
)

// Physical echo requests use unprivileged ping sockets. Tunneled ICMP keeps its
// original packets and identifiers through the WireGuard TUN mux.
func (r *Runtime) forwardICMP(packet []byte) {
	headerSize := int(packet[0]&15) * 4
	if headerSize < 20 || len(packet) < headerSize+8 {
		return
	}
	address := netip.AddrFrom4([4]byte(packet[16:20]))
	if r.router.tunnel(address, time.Now()) {
		r.mu.Lock()
		device := r.tunnelDevice
		r.mu.Unlock()
		if device != nil {
			device.enqueue(packet)
		}
		return
	}
	if packet[headerSize] != 8 || packet[headerSize+1] != 0 {
		return
	}
	if binary.BigEndian.Uint16(packet[6:8])&0x3fff != 0 || !r.acquire() {
		return
	}
	request := append([]byte(nil), packet...)
	go func() {
		defer r.finishFlow()
		address := netip.AddrFrom4([4]byte(request[16:20]))
		payload := request[headerSize:]
		conn, err := icmp.ListenPacket("udp4", "0.0.0.0")
		if err != nil {
			return
		}
		if !r.track(conn) {
			conn.Close()
			return
		}
		defer r.release(conn)
		conn.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := conn.WriteTo(payload, &net.UDPAddr{IP: net.IP(address.AsSlice())}); err != nil {
			return
		}
		incoming := make([]byte, 65535)
		n, _, err := conn.ReadFrom(incoming)
		if err != nil {
			return
		}
		reply := incoming[:n]
		if len(reply) < 8 || len(reply) > 65515 || reply[0] != 0 || binary.BigEndian.Uint16(reply[6:8]) != binary.BigEndian.Uint16(payload[6:8]) {
			return
		}
		// Ping sockets may replace the identifier. The application keeps its own.
		copy(reply[4:6], payload[4:6])
		reply[2], reply[3] = 0, 0
		binary.BigEndian.PutUint16(reply[2:4], checksum(reply))
		response := make([]byte, 20+len(reply))
		response[0], response[8], response[9] = 0x45, 64, 1
		binary.BigEndian.PutUint16(response[2:4], uint16(len(response)))
		copy(response[12:16], request[16:20])
		copy(response[16:20], request[12:16])
		binary.BigEndian.PutUint16(response[10:12], checksum(response[:20]))
		copy(response[20:], reply)
		r.writeMu.Lock()
		r.tun.Write([][]byte{response}, 0)
		r.writeMu.Unlock()
	}()
}

func checksum(data []byte) uint16 {
	var total uint32
	for len(data) >= 2 {
		total += uint32(binary.BigEndian.Uint16(data))
		data = data[2:]
	}
	if len(data) > 0 {
		total += uint32(data[0]) << 8
	}
	for total>>16 != 0 {
		total = (total & 0xffff) + (total >> 16)
	}
	return ^uint16(total)
}
