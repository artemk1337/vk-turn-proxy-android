package mobile

import (
	"strings"
	"testing"
)

func TestSafeBoundedLogs(t *testing.T) {
	e := NewEngine()
	if err := e.SetLogLimit(100); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 250; i++ {
		e.recordStatus("connecting", "Transport connection failed, retrying", int32(i))
	}
	lines := strings.Split(e.Logs(), "\n")
	if len(lines) != 100 {
		t.Fatalf("got %d log lines", len(lines))
	}
	if !strings.Contains(lines[len(lines)-1], "active_connections=249") {
		t.Fatal("latest event missing")
	}
	e.recordStatus("connecting", "Transport connection failed, retrying", 249)
	if e.Logs() != strings.Join(lines, "\n") {
		t.Fatal("unchanged status duplicated in log")
	}
	for _, limit := range []int{0, 99, 10001} {
		if e.SetLogLimit(limit) == nil {
			t.Fatal("invalid log limit accepted")
		}
	}
}
