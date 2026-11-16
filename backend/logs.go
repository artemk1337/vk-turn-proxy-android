// SPDX-License-Identifier: MIT
package mobile

import (
	"errors"
	"fmt"
	"strings"
	"time"

	desktopsettings "github.com/artemk1337/vk-turn-proxy-desktop/pkg/desktop-settings"
)

// SetLogLimit bounds safe diagnostic events using the shared desktop limits.
func (e *Engine) SetLogLimit(lines int) error {
	rangeLimit := desktopsettings.Defaults().LogLines
	if lines < rangeLimit.Min || lines > rangeLimit.Max {
		return errors.New("log limit must be between 100 and 10000 lines")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.logLimit = lines
	if len(e.logs) > lines {
		e.logs = e.logs[len(e.logs)-lines:]
	}
	return nil
}

// Logs returns bounded structured events. Raw transport logs are never captured.
func (e *Engine) Logs() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return strings.Join(e.logs, "\n")
}

func (e *Engine) appendLogLocked(message string) {
	if e.logLimit == 0 {
		e.logLimit = desktopsettings.Defaults().LogLines.Default
	}
	e.logs = append(e.logs, time.Now().Format(time.RFC3339)+" "+message)
	if len(e.logs) > e.logLimit {
		e.logs = e.logs[len(e.logs)-e.logLimit:]
	}
}

func (e *Engine) recordStatus(state, message string, active int32) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.lastLogState != state || e.lastLogMessage != message || e.lastLogActive != active {
		e.appendLogLocked(fmt.Sprintf("state=%s active_connections=%d %s", state, active, message))
		e.lastLogState, e.lastLogMessage, e.lastLogActive = state, message, active
	}
}
