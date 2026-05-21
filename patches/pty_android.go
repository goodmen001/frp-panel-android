//go:build android

package pty

import "errors"

type Pty struct{}

func DownloadDependency() error { return nil }

func Start() (PTYInterface, error) {
	return nil, errors.New("PTY not supported on Android")
}

func (p *Pty) Write(p []byte) (n int, err error)  { return 0, errors.New("not supported") }
func (p *Pty) Read(p []byte) (n int, err error)   { return 0, errors.New("not supported") }
func (p *Pty) Getsize() (uint16, uint16, error)   { return 0, 0, errors.New("not supported") }
func (p *Pty) Setsize(cols, rows uint32) error    { return errors.New("not supported") }
func (p *Pty) Close() error                       { return nil }
