package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/VaalaCat/frp-panel/pb"
	"github.com/VaalaCat/frp-panel/utils/wsgrpc"
	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	"github.com/fatedier/frp/pkg/featuregate"
	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/proto"
)

type tunnelManager struct {
	mu       sync.Mutex
	services map[string]*client.Service
	cfgs     map[string]*v1.ClientCommonConfig
}

func newTunnelManager() *tunnelManager {
	return &tunnelManager{
		services: make(map[string]*client.Service),
		cfgs:     make(map[string]*v1.ClientCommonConfig),
	}
}

func (tm *tunnelManager) key(clientID, serverID string) string {
	return clientID + "/" + serverID
}

func (tm *tunnelManager) getOrCreate(
	clientID, serverID string,
	commonCfg *v1.ClientCommonConfig,
	proxyCfgs []v1.ProxyConfigurer,
	visitorCfgs []v1.VisitorConfigurer,
) (*client.Service, error) {
	tm.mu.Lock()
	defer tm.mu.Unlock()

	k := tm.key(clientID, serverID)
	if svc, ok := tm.services[k]; ok {
		if cfg, ok := tm.cfgs[k]; ok && !commonConfigsEqual(cfg, commonCfg) {
			svc.Close()
			delete(tm.services, k)
			delete(tm.cfgs, k)
			return tm.createAndStore(k, commonCfg, proxyCfgs, visitorCfgs)
		}
		svc.UpdateAllConfigurer(proxyCfgs, visitorCfgs)
		return svc, nil
	}
	return tm.createAndStore(k, commonCfg, proxyCfgs, visitorCfgs)
}

func (tm *tunnelManager) createAndStore(
	k string,
	commonCfg *v1.ClientCommonConfig,
	proxyCfgs []v1.ProxyConfigurer,
	visitorCfgs []v1.VisitorConfigurer,
) (*client.Service, error) {
	if len(commonCfg.FeatureGates) > 0 {
		if err := featuregate.SetFromMap(commonCfg.FeatureGates); err != nil {
			fmt.Fprintf(os.Stderr, "feature gate error: %v\n", err)
		}
	}
	warning, err := validation.ValidateAllClientConfig(commonCfg, proxyCfgs, visitorCfgs)
	if warning != nil {
		fmt.Fprintf(os.Stderr, "config warning: %v\n", warning)
	}
	if err != nil {
		return nil, fmt.Errorf("invalid config: %w", err)
	}
	svc, err := client.NewService(client.ServiceOptions{
		Common:      commonCfg,
		ProxyCfgs:   proxyCfgs,
		VisitorCfgs: visitorCfgs,
	})
	if err != nil {
		return nil, err
	}
	tm.services[k] = svc
	tm.cfgs[k] = commonCfg
	return svc, nil
}

func (tm *tunnelManager) stopAll() {
	tm.mu.Lock()
	defer tm.mu.Unlock()
	for k, svc := range tm.services {
		svc.Close()
		delete(tm.services, k)
		delete(tm.cfgs, k)
	}
}

func commonConfigsEqual(a, b *v1.ClientCommonConfig) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.ServerAddr == b.ServerAddr &&
		a.ServerPort == b.ServerPort &&
		a.User == b.User
}

func loadClientConfig(content []byte) (*v1.ClientCommonConfig, []v1.ProxyConfigurer, []v1.VisitorConfigurer, error) {
	rendered, err := config.RenderWithTemplate(content, config.GetValues())
	if err != nil {
		return nil, nil, nil, fmt.Errorf("render template: %w", err)
	}
	allCfg := v1.ClientConfig{}
	if err := config.LoadConfigure(rendered, &allCfg, false); err != nil {
		return nil, nil, nil, fmt.Errorf("load configure: %w", err)
	}

	cliCfg := &allCfg.ClientCommonConfig
	proxyCfgs := make([]v1.ProxyConfigurer, 0, len(allCfg.Proxies))
	for _, c := range allCfg.Proxies {
		proxyCfgs = append(proxyCfgs, c.ProxyConfigurer)
	}
	visitorCfgs := make([]v1.VisitorConfigurer, 0, len(allCfg.Visitors))
	for _, c := range allCfg.Visitors {
		visitorCfgs = append(visitorCfgs, c.VisitorConfigurer)
	}

	if len(cliCfg.Start) > 0 {
		startSet := make(map[string]struct{}, len(cliCfg.Start))
		for _, s := range cliCfg.Start {
			startSet[s] = struct{}{}
		}
		filteredProxies := make([]v1.ProxyConfigurer, 0, len(proxyCfgs))
		for _, p := range proxyCfgs {
			if _, ok := startSet[p.GetBaseConfig().Name]; ok {
				filteredProxies = append(filteredProxies, p)
			}
		}
		proxyCfgs = filteredProxies
		filteredVisitors := make([]v1.VisitorConfigurer, 0, len(visitorCfgs))
		for _, v := range visitorCfgs {
			if _, ok := startSet[v.GetBaseConfig().Name]; ok {
				filteredVisitors = append(filteredVisitors, v)
			}
		}
		visitorCfgs = filteredVisitors
	}

	cliCfg.Complete()
	for _, c := range proxyCfgs {
		c.Complete(cliCfg.User)
	}
	for _, c := range visitorCfgs {
		c.Complete(cliCfg)
	}
	return cliCfg, proxyCfgs, visitorCfgs, nil
}

type clientApp struct {
	clientID      string
	clientSecret  string
	rpcURL        string
	skipTLSVerify bool

	tunnels *tunnelManager

	masterCliValue atomic.Value // stores pb.MasterClient

	stopCh chan struct{}
	wg     sync.WaitGroup
}

func newClientApp() *clientApp {
	return &clientApp{
		tunnels: newTunnelManager(),
		stopCh:  make(chan struct{}),
	}
}

// --- Main connection loop ---

func (a *clientApp) run() {
	a.wg.Add(1)
	go a.pollConfigLoop()

	for {
		select {
		case <-a.stopCh:
			return
		default:
		}

		err := a.connectAndServe()
		if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, io.EOF) {
			fmt.Fprintf(os.Stderr, "connection error: %v, reconnecting in 5s\n", err)
		}

		select {
		case <-a.stopCh:
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func (a *clientApp) connectAndServe() error {
	header := http.Header{}
	dialer := wsgrpc.WebsocketDialer(a.rpcURL, header, a.skipTLSVerify, &nopLogger{})

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	conn, err := grpc.DialContext(ctx, "ignored",
		grpc.WithContextDialer(dialer),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return fmt.Errorf("dial failed: %w", err)
	}
	defer conn.Close()

	a.setMasterCli(pb.NewMasterClient(conn))

	stream, err := a.masterCli().ServerSend(context.Background())
	if err != nil {
		return fmt.Errorf("server send stream failed: %w", err)
	}

	fmt.Fprintf(os.Stdout, "registering client %s\n", a.clientID)
	if err := a.register(stream); err != nil {
		return fmt.Errorf("register failed: %w", err)
	}
	fmt.Fprintf(os.Stdout, "client registered successfully\n")

	msgCh := make(chan *pb.ServerMessage, 64)
	errCh := make(chan error, 1)

	a.wg.Add(1)
	go func() {
		defer a.wg.Done()
		for {
			msg, err := stream.Recv()
			if err != nil {
				errCh <- err
				return
			}
			select {
			case msgCh <- msg:
			case <-a.stopCh:
				return
			}
		}
	}()

	for {
		select {
		case <-a.stopCh:
			stream.CloseSend()
			return nil
		case err := <-errCh:
			if err == io.EOF {
				return nil
			}
			return fmt.Errorf("recv error: %w", err)
		case msg := <-msgCh:
			resp := a.handleServerMessage(msg)
			if resp != nil {
				resp.ClientId = a.clientID
				resp.SessionId = msg.SessionId
				if err := stream.Send(resp); err != nil {
					fmt.Fprintf(os.Stderr, "send response error: %v\n", err)
				}
			}
		}
	}
}

func (a *clientApp) register(stream pb.Master_ServerSendClient) error {
	for i := 0; i < 10; i++ {
		err := stream.Send(&pb.ClientMessage{
			Event:     pb.Event_EVENT_REGISTER_CLIENT,
			ClientId:  a.clientID,
			SessionId: uuid.New().String(),
			Secret:    a.clientSecret,
		})
		if err != nil {
			if i < 9 {
				time.Sleep(3 * time.Second)
				continue
			}
			return fmt.Errorf("send register failed: %w", err)
		}

		resp, err := stream.Recv()
		if err != nil {
			if i < 9 {
				time.Sleep(3 * time.Second)
				continue
			}
			return fmt.Errorf("recv register response failed: %w", err)
		}

		if resp.GetEvent() == pb.Event_EVENT_REGISTER_CLIENT {
			return nil
		}
		time.Sleep(3 * time.Second)
	}
	return errors.New("register timed out")
}

// --- Config polling ---

func (a *clientApp) pollConfigLoop() {
	defer a.wg.Done()

	// Wait for first connection (masterCli becomes non-nil)
	for {
		if a.masterCli() != nil {
			break
		}
		select {
		case <-a.stopCh:
			return
		case <-time.After(time.Second):
		}
	}

	// Initial pull shortly after connection
	time.Sleep(2 * time.Second)
	a.pullAndApplyConfig()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-a.stopCh:
			return
		case <-ticker.C:
			a.pullAndApplyConfig()
		}
	}
}

func (a *clientApp) pullAndApplyConfig() {
	cli := a.masterCli()
	if cli == nil {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	resp, err := cli.PullClientConfig(ctx, &pb.PullClientConfigReq{
		Base: &pb.ClientBase{
			ClientId:     a.clientID,
			ClientSecret: a.clientSecret,
		},
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "pull config error: %v\n", err)
		return
	}

	clientData := resp.GetClient()
	if clientData == nil {
		return
	}

	if clientData.GetStopped() {
		a.tunnels.stopAll()
		return
	}

	configContent := clientData.GetConfig()
	if len(configContent) == 0 {
		return
	}

	cfg, proxyCfgs, visitorCfgs, err := loadClientConfig([]byte(configContent))
	if err != nil {
		fmt.Fprintf(os.Stderr, "load config error: %v\n", err)
		return
	}

	serverID := clientData.GetServerId()
	svc, err := a.tunnels.getOrCreate(a.clientID, serverID, cfg, proxyCfgs, visitorCfgs)
	if err != nil {
		fmt.Fprintf(os.Stderr, "create service error: %v\n", err)
		return
	}

	go func() {
		fmt.Fprintf(os.Stdout, "starting frpc service from pull config\n")
		ctx := context.Background()
		if err := svc.Run(ctx); err != nil {
			fmt.Fprintf(os.Stderr, "frpc service error: %v\n", err)
		}
	}()
}

func (a *clientApp) masterCli() pb.MasterClient {
	cli, _ := a.masterCliValue.Load().(pb.MasterClient)
	return cli
}

func (a *clientApp) setMasterCli(cli pb.MasterClient) {
	a.masterCliValue.Store(cli)
}

// --- Server message handlers ---

func (a *clientApp) handleServerMessage(req *pb.ServerMessage) *pb.ClientMessage {
	switch req.Event {
	case pb.Event_EVENT_UPDATE_FRPC:
		return a.handleUpdateFrpc(req)
	case pb.Event_EVENT_REMOVE_FRPC:
		return a.handleRemoveFrpc(req)
	case pb.Event_EVENT_START_FRPC:
		return a.handleStartFrpc(req)
	case pb.Event_EVENT_STOP_FRPC:
		return a.handleStopFrpc(req)
	case pb.Event_EVENT_GET_PROXY_INFO:
		return a.handleGetProxyInfo(req)
	case pb.Event_EVENT_PING:
		return &pb.ClientMessage{
			Event: pb.Event_EVENT_PONG,
		}
	case pb.Event_EVENT_START_STREAM_LOG:
		return errorResp("stream log not supported on Android")
	case pb.Event_EVENT_STOP_STREAM_LOG:
		return errorResp("stream log not supported on Android")
	case pb.Event_EVENT_START_PTY_CONNECT:
		return errorResp("PTY not supported on Android")
	case pb.Event_EVENT_CREATE_WORKER:
		return errorResp("workers not supported on Android")
	case pb.Event_EVENT_REMOVE_WORKER:
		return errorResp("workers not supported on Android")
	case pb.Event_EVENT_GET_WORKER_STATUS:
		return errorResp("workers not supported on Android")
	case pb.Event_EVENT_INSTALL_WORKERD:
		return errorResp("workerd not supported on Android")
	case pb.Event_EVENT_CREATE_WIREGUARD:
		return errorResp("WireGuard not supported on Android")
	case pb.Event_EVENT_DELETE_WIREGUARD:
		return errorResp("WireGuard not supported on Android")
	case pb.Event_EVENT_UPDATE_WIREGUARD:
		return errorResp("WireGuard not supported on Android")
	case pb.Event_EVENT_GET_WIREGUARD_RUNTIME_INFO:
		return errorResp("WireGuard not supported on Android")
	case pb.Event_EVENT_RESTART_WIREGUARD:
		return errorResp("WireGuard not supported on Android")
	case pb.Event_EVENT_UPGRADE_FRPP:
		return errorResp("upgrade not supported on Android")
	default:
		return nil
	}
}

func (a *clientApp) handleUpdateFrpc(req *pb.ServerMessage) *pb.ClientMessage {
	updateReq := &pb.UpdateFRPCRequest{}
	if err := proto.Unmarshal(req.GetData(), updateReq); err != nil {
		return errorResp("unmarshal UpdateFRPCRequest failed: " + err.Error())
	}

	content := updateReq.GetConfig()
	if len(content) == 0 {
		return okResp("empty config, nothing to do")
	}

	cfg, proxyCfgs, visitorCfgs, err := loadClientConfig(content)
	if err != nil {
		return errorResp("load config failed: " + err.Error())
	}

	clientID := updateReq.GetClientId()
	if clientID == "" {
		clientID = a.clientID
	}
	serverID := updateReq.GetServerId()

	svc, err := a.tunnels.getOrCreate(clientID, serverID, cfg, proxyCfgs, visitorCfgs)
	if err != nil {
		return errorResp("create service failed: " + err.Error())
	}

	go func() {
		fmt.Fprintf(os.Stdout, "starting frpc service clientID=%s serverID=%s\n", clientID, serverID)
		ctx := context.Background()
		if err := svc.Run(ctx); err != nil {
			fmt.Fprintf(os.Stderr, "frpc service error: %v\n", err)
		}
	}()

	return okResp("frpc updated")
}

func (a *clientApp) handleStartFrpc(req *pb.ServerMessage) *pb.ClientMessage {
	startReq := &pb.StartFRPCRequest{}
	if err := proto.Unmarshal(req.GetData(), startReq); err != nil {
		return errorResp("unmarshal StartFRPCRequest failed: " + err.Error())
	}

	fmt.Fprintf(os.Stdout, "pulling config from master for client %s\n", a.clientID)

	cli := a.masterCli()
	if cli == nil {
		return errorResp("not connected")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	pullResp, err := cli.PullClientConfig(ctx, &pb.PullClientConfigReq{
		Base: &pb.ClientBase{
			ClientId:     a.clientID,
			ClientSecret: a.clientSecret,
		},
	})
	if err != nil {
		return errorResp("pull config failed: " + err.Error())
	}

	configContent := pullResp.GetClient().GetConfig()
	if len(configContent) == 0 {
		return okResp("no config from master")
	}

	cfg, proxyCfgs, visitorCfgs, err := loadClientConfig([]byte(configContent))
	if err != nil {
		return errorResp("load pulled config failed: " + err.Error())
	}

	serverID := pullResp.GetClient().GetServerId()
	svc, err := a.tunnels.getOrCreate(a.clientID, serverID, cfg, proxyCfgs, visitorCfgs)
	if err != nil {
		return errorResp("create service failed: " + err.Error())
	}

	go func() {
		fmt.Fprintf(os.Stdout, "starting frpc service clientID=%s serverID=%s\n", a.clientID, serverID)
		ctx := context.Background()
		if err := svc.Run(ctx); err != nil {
			fmt.Fprintf(os.Stderr, "frpc service error: %v\n", err)
		}
	}()

	return okResp("frpc started")
}

func (a *clientApp) handleStopFrpc(req *pb.ServerMessage) *pb.ClientMessage {
	a.tunnels.stopAll()
	return okResp("all frpc stopped")
}

func (a *clientApp) handleRemoveFrpc(req *pb.ServerMessage) *pb.ClientMessage {
	a.tunnels.stopAll()
	return okResp("all frpc removed")
}

func (a *clientApp) handleGetProxyInfo(req *pb.ServerMessage) *pb.ClientMessage {
	getReq := &pb.GetProxyConfigRequest{}
	if err := proto.Unmarshal(req.GetData(), getReq); err != nil {
		return errorResp("unmarshal GetProxyConfigRequest failed: " + err.Error())
	}

	serverID := getReq.GetServerId()
	proxyName := getReq.GetName()

	k := a.tunnels.key(a.clientID, serverID)
	a.tunnels.mu.Lock()
	svc, ok := a.tunnels.services[k]
	a.tunnels.mu.Unlock()

	if !ok {
		return errorResp("no running service")
	}

	exporter := svc.StatusExporter()
	status, ok := exporter.GetProxyStatus(proxyName)
	if !ok {
		return errorResp("proxy not found: " + proxyName)
	}

	resp := &pb.GetProxyConfigResponse{
		Status: &pb.Status{Code: pb.RespCode_RESP_CODE_SUCCESS},
		WorkingStatus: &pb.ProxyWorkingStatus{
			Name:       &status.Name,
			Type:       &status.Type,
			Status:     &status.Phase,
			Err:        &status.Err,
			RemoteAddr: &status.RemoteAddr,
		},
	}
	data, _ := proto.Marshal(resp)
	return &pb.ClientMessage{
		Event: pb.Event_EVENT_GET_PROXY_INFO,
		Data:  data,
	}
}

func errorResp(msg string) *pb.ClientMessage {
	return &pb.ClientMessage{
		Event: pb.Event_EVENT_ERROR,
		Data:  []byte(msg),
	}
}

func okResp(msg string) *pb.ClientMessage {
	return &pb.ClientMessage{
		Event: pb.Event_EVENT_DATA,
		Data:  []byte(msg),
	}
}

type nopLogger struct{}

func (n *nopLogger) Infof(format string, args ...interface{})  {}
func (n *nopLogger) Errorf(format string, args ...interface{}) {}
func (n *nopLogger) Tracef(format string, args ...interface{}) {}

// normalizeRPCURL ensures the WebSocket gRPC URL includes the /wsgrpc path.
// The frp-panel master mounts the gRPC WebSocket handler at /wsgrpc.
// If the URL already has a path (not just "/"), it is left unchanged.
func normalizeRPCURL(url string) string {
	if url == "" {
		return url
	}
	// If the URL already contains a non-empty path (other than bare "/"), return as-is.
	if strings.Contains(url, "/wsgrpc") {
		return url
	}
	// Strip trailing slash and append /wsgrpc
	url = strings.TrimRight(url, "/")
	return url + "/wsgrpc"
}

func main() {
	app := newClientApp()
	app.clientID = os.Getenv("CLIENT_ID")
	app.clientSecret = os.Getenv("CLIENT_SECRET")
	app.rpcURL = normalizeRPCURL(os.Getenv("CLIENT_RPC_URL"))
	tlsRPC := os.Getenv("CLIENT_TLS_RPC")
	app.skipTLSVerify = tlsRPC != "true" && tlsRPC != "1"

	if app.clientID == "" {
		fmt.Fprintf(os.Stderr, "CLIENT_ID must be set\n")
		os.Exit(1)
	}
	if app.clientSecret == "" {
		fmt.Fprintf(os.Stderr, "CLIENT_SECRET must be set\n")
		os.Exit(1)
	}
	if app.rpcURL == "" {
		fmt.Fprintf(os.Stderr, "CLIENT_RPC_URL must be set\n")
		os.Exit(1)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-sigCh
		fmt.Fprintf(os.Stdout, "shutting down...\n")
		close(app.stopCh)
		app.tunnels.stopAll()
	}()

	fmt.Fprintf(os.Stdout, "frppc-android starting: id=%s url=%s\n", app.clientID, app.rpcURL)
	app.run()
}
