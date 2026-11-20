//go:build e2e

// The verification harness links the automation plugin. Release builds leave
// this file out, so the socket never exists in a shipped app.
package main

import _ "github.com/egoist/mygo/plugins/automation"
