# Routing runtime

Follow the parent [AGENTS.md](../../AGENTS.md).

`runtime.go` forwards Android IPv4 TCP/UDP sessions through physical sockets or the WireGuard netstack. `policy.go` owns immutable routing rules and bounded DNS-learned address entries. `dns.go` learns matching A/CNAME/HTTPS/SVCB routes before returning DNS answers. `tunnel-device.go` preserves raw tunneled ICMP and sends ICMP errors for forwarded TCP/UDP sessions back to their netstack. Physical echo uses unprivileged ping sockets. IPv6 is dropped at the Android TUN boundary. Configured VPN DNS always uses WireGuard.

Cancellation closes both TUN loops and all accepted connections. Keep flow counts and DNS entries bounded. Domain-derived routes expire with DNS TTL and never override local-direct or configured VPN DNS. Run `go test -race ./routing-runtime` from `backend` after changes.
