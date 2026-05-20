package frpcengine

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	"github.com/fatedier/frp/client"
)

// FrpcEngine is the main engine exported via gomobile to Android.
// It communicates with frp-panel master via REST API, retrieves
// tunnel configuration, and runs frpc using the fatedier/frp library.
type FrpcEngine struct {
	mu       sync.Mutex
	api      *apiClient
	runner   *frpcRunner
	cancel   context.CancelFunc
	wg       sync.WaitGroup
	status   string

	// cached for config polling
	masterURL string
	username  string
	password  string
	clientID  string
}

type frpcRunner struct {
	svc    *client.Service
	cfg    *v1.ClientConfig
	cancel context.CancelFunc
	wg     sync.WaitGroup
	running bool
	mu      sync.Mutex
}

// NewFrpcEngine creates a new stopped engine.
func NewFrpcEngine() *FrpcEngine {
	return &FrpcEngine{status: "Stopped"}
}

// Start connects to the master, authenticates, fetches the frpc config
// for the given client ID, and starts proxying tunnels.
func (e *FrpcEngine) Start(masterURL, username, password, clientID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.runner != nil && e.runner.isRunning() {
		return fmt.Errorf("engine already running")
	}

	e.status = "Connecting..."
	e.masterURL = masterURL
	e.username = username
	e.password = password
	e.clientID = clientID

	// 1. Login to master
	api := newAPIClient(masterURL)
	token, err := api.login(username, password)
	if err != nil {
		e.status = "Login failed"
		return fmt.Errorf("login: %w", err)
	}
	api.setToken(token)

	// 2. Fetch client config
	e.status = "Fetching config..."
	configJSON, err := api.getClientConfig(clientID)
	if err != nil {
		e.status = "Config fetch failed"
		return fmt.Errorf("get config: %w", err)
	}

	// 3. Normalize and parse config
	cfg, err := parseConfigJSON(configJSON)
	if err != nil {
		e.status = "Config parse failed"
		return fmt.Errorf("parse config: %w", err)
	}

	// 4. Start frpc runner
	e.status = "Starting frpc..."
	runner, err := newRunner(cfg)
	if err != nil {
		e.status = "Start failed"
		return fmt.Errorf("new runner: %w", err)
	}
	if err := runner.start(); err != nil {
		e.status = "Start failed"
		return fmt.Errorf("runner start: %w", err)
	}

	// 5. Start config polling (every 30 seconds)
	ctx, cancel := context.WithCancel(context.Background())
	e.cancel = cancel
	e.wg.Add(1)
	go e.pollLoop(ctx, api, clientID, runner)

	e.api = api
	e.runner = runner
	e.status = "Running"
	log.Printf("frpc-engine: started client=%s", clientID)
	return nil
}

// Stop gracefully stops frpc and polling.
func (e *FrpcEngine) Stop() {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.cancel != nil {
		e.cancel()
		e.wg.Wait()
		e.cancel = nil
	}
	if e.runner != nil {
		e.runner.stop()
		e.runner = nil
	}
	e.api = nil
	e.status = "Stopped"
	log.Printf("frpc-engine: stopped")
}

// IsRunning reports whether the engine is active.
func (e *FrpcEngine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.runner != nil && e.runner.isRunning()
}

// GetStatus returns a human-readable status.
func (e *FrpcEngine) GetStatus() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.runner != nil && e.runner.isRunning() {
		return e.status
	}
	if e.status != "Stopped" {
		return "Error"
	}
	return e.status
}

// --- polling ---

func (e *FrpcEngine) pollLoop(ctx context.Context, api *apiClient, clientID string, run *frpcRunner) {
	defer e.wg.Done()
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			e.checkUpdate(ctx, api, clientID, run)
		}
	}
}

func (e *FrpcEngine) checkUpdate(ctx context.Context, api *apiClient, clientID string, run *frpcRunner) {
	raw, err := api.getClientConfig(clientID)
	if err != nil {
		log.Printf("frpc-engine: poll config error: %v", err)
		return
	}
	if run.configMatches(raw) {
		return
	}
	log.Printf("frpc-engine: config changed, reconfiguring")
	cfg, err := parseConfigJSON(raw)
	if err != nil {
		log.Printf("frpc-engine: parse updated config: %v", err)
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	// Re-check runner hasn't been swapped
	if e.runner != run {
		return
	}
	if err := run.update(cfg); err != nil {
		log.Printf("frpc-engine: update failed, restarting: %v", err)
		e.status = "Restarting..."
		run.stop()
		newRun, err := newRunner(cfg)
		if err != nil {
			e.status = "Restart failed"
			log.Printf("frpc-engine: new runner: %v", err)
			return
		}
		if err := newRun.start(); err != nil {
			e.status = "Restart failed"
			log.Printf("frpc-engine: runner start: %v", err)
			return
		}
		e.runner = newRun
	}
	e.status = "Running"
}

// --- config parsing ---

// parseConfigJSON normalizes frp-panel config JSON and parses it into v1.ClientConfig.
// frp-panel uses Proxies/Visitors (capital) while fatedier/frp expects proxies/visitors.
func parseConfigJSON(data []byte) (*v1.ClientConfig, error) {
	// Normalize field names: Proxies -> proxies, Visitors -> visitors
	normalized, err := normalizeKeys(data)
	if err != nil {
		return nil, fmt.Errorf("normalize keys: %w", err)
	}

	// Parse as fatedier/frp ClientConfig
	cfg := &v1.ClientConfig{}
	if err := config.LoadConfigure(normalized, cfg, false); err != nil {
		return nil, fmt.Errorf("load config: %w", err)
	}
	cfg.Complete()

	// Extract proxies and visitors from typed wrappers
	proxyCfgs := make([]v1.ProxyConfigurer, 0, len(cfg.Proxies))
	for _, p := range cfg.Proxies {
		if p.ProxyConfigurer != nil {
			p.ProxyConfigurer.Complete(cfg.User)
			proxyCfgs = append(proxyCfgs, p.ProxyConfigurer)
		}
	}
	visitorCfgs := make([]v1.VisitorConfigurer, 0, len(cfg.Visitors))
	for _, v := range cfg.Visitors {
		if v.VisitorConfigurer != nil {
			v.VisitorConfigurer.Complete(&cfg.ClientCommonConfig)
			visitorCfgs = append(visitorCfgs, v.VisitorConfigurer)
		}
	}

	// Validate
	warn, err := validation.ValidateAllClientConfig(&cfg.ClientCommonConfig, proxyCfgs, visitorCfgs)
	if err != nil {
		return nil, fmt.Errorf("validation: %w (warning: %v)", err, warn)
	}
	if warn != nil {
		log.Printf("frpc-engine: config warning: %v", warn)
	}

	return cfg, nil
}

// --- runner ---

func newRunner(cfg *v1.ClientConfig) (*frpcRunner, error) {
	proxyCfgs := make([]v1.ProxyConfigurer, 0, len(cfg.Proxies))
	for _, p := range cfg.Proxies {
		if p.ProxyConfigurer != nil {
			proxyCfgs = append(proxyCfgs, p.ProxyConfigurer)
		}
	}
	visitorCfgs := make([]v1.VisitorConfigurer, 0, len(cfg.Visitors))
	for _, v := range cfg.Visitors {
		if v.VisitorConfigurer != nil {
			visitorCfgs = append(visitorCfgs, v.VisitorConfigurer)
		}
	}

	svc, err := client.NewService(client.ServiceOptions{
		Common:      &cfg.ClientCommonConfig,
		ProxyCfgs:   proxyCfgs,
		VisitorCfgs: visitorCfgs,
	})
	if err != nil {
		return nil, fmt.Errorf("new frpc service: %w", err)
	}

	return &frpcRunner{svc: svc, cfg: cfg}, nil
}

func (r *frpcRunner) start() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.running {
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	r.cancel = cancel
	r.running = true
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		if err := r.svc.Run(ctx); err != nil {
			log.Printf("frpc-engine: service exited: %v", err)
		}
		r.mu.Lock()
		r.running = false
		r.mu.Unlock()
	}()
	return nil
}

func (r *frpcRunner) stop() {
	r.mu.Lock()
	if r.running {
		r.svc.Close()
		r.cancel()
	}
	r.mu.Unlock()
	r.wg.Wait()
	r.mu.Lock()
	r.cancel = nil
	r.running = false
	r.mu.Unlock()
}

func (r *frpcRunner) isRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.running
}

func (r *frpcRunner) configMatches(raw []byte) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cfg == nil {
		return false
	}
	cur, err := json.Marshal(r.cfg)
	if err != nil {
		return false
	}
	// Normalize raw to handle Proxies -> proxies
	normalized, err := normalizeKeys(raw)
	if err != nil {
		return false
	}
	var a, b map[string]any
	json.Unmarshal(cur, &a)
	json.Unmarshal(normalized, &b)
	return jsonMarshalEqual(a, b)
}

func (r *frpcRunner) update(cfg *v1.ClientConfig) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Check if common config changed (needs restart vs hot-reload)
	oldJSON, _ := json.Marshal(r.cfg.ClientCommonConfig)
	newJSON, _ := json.Marshal(cfg.ClientCommonConfig)
	commonChanged := !jsonMarshalEqualBytes(oldJSON, newJSON)

	proxyCfgs := make([]v1.ProxyConfigurer, 0, len(cfg.Proxies))
	for _, p := range cfg.Proxies {
		if p.ProxyConfigurer != nil {
			proxyCfgs = append(proxyCfgs, p.ProxyConfigurer)
		}
	}
	visitorCfgs := make([]v1.VisitorConfigurer, 0, len(cfg.Visitors))
	for _, v := range cfg.Visitors {
		if v.VisitorConfigurer != nil {
			visitorCfgs = append(visitorCfgs, v.VisitorConfigurer)
		}
	}

	if commonChanged {
		// Full restart needed
		log.Printf("frpc-engine: common config changed, restarting")
		r.svc.Close()
		r.cancel()
		r.wg.Wait()

		newSvc, err := client.NewService(client.ServiceOptions{
			Common:      &cfg.ClientCommonConfig,
			ProxyCfgs:   proxyCfgs,
			VisitorCfgs: visitorCfgs,
		})
		if err != nil {
			return fmt.Errorf("new service: %w", err)
		}
		r.svc = newSvc
		r.cfg = cfg

		ctx, cancel := context.WithCancel(context.Background())
		r.cancel = cancel
		r.running = true
		r.wg.Add(1)
		go func() {
			defer r.wg.Done()
			newSvc.Run(ctx)
			r.mu.Lock()
			r.running = false
			r.mu.Unlock()
		}()
		return nil
	}

	// Hot-reload proxies and visitors
	if err := r.svc.UpdateAllConfigurer(proxyCfgs, visitorCfgs); err != nil {
		return fmt.Errorf("hot-reload: %w", err)
	}
	r.cfg = cfg
	log.Printf("frpc-engine: hot-reloaded %d proxies, %d visitors", len(proxyCfgs), len(visitorCfgs))
	return nil
}

// --- helpers ---

// normalizeKeys renames Proxies->proxies and Visitors->visitors in JSON.
func normalizeKeys(data []byte) ([]byte, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	if v, ok := raw["Proxies"]; ok {
		raw["proxies"] = v
	}
	if v, ok := raw["Visitors"]; ok {
		raw["visitors"] = v
	}
	return json.Marshal(raw)
}

func jsonMarshalEqualBytes(a, b []byte) bool {
	var va, vb any
	json.Unmarshal(a, &va)
	json.Unmarshal(b, &vb)
	return jsonMarshalEqual(va, vb)
}

func jsonMarshalEqual(a, b any) bool {
	aj, _ := json.Marshal(a)
	bj, _ := json.Marshal(b)
	return string(aj) == string(bj)
}
