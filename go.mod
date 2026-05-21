module github.com/goodmen001/frp-panel-android

go 1.24

require (
	github.com/VaalaCat/frp-panel v0.0.0
	github.com/fatedier/frp v0.65.0
	github.com/google/uuid v1.6.0
	google.golang.org/grpc v1.67.1
	google.golang.org/protobuf v1.36.5
)

require (
	github.com/gorilla/websocket v1.5.3
	google.golang.org/genproto/googleapis/rpc v0.0.0-20240814211410-ddb44dafa142
)

replace github.com/VaalaCat/frp-panel => ../frp-panel-source
