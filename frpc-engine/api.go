package frpcengine

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// apiClient communicates with the frp-panel master via REST JSON API.
type apiClient struct {
	baseURL  string
	token    string
	httpCli  *http.Client
}

func newAPIClient(baseURL string) *apiClient {
	baseURL = strings.TrimRight(baseURL, "/")
	return &apiClient{
		baseURL: baseURL,
		httpCli: &http.Client{},
	}
}

func (c *apiClient) setToken(token string) {
	c.token = token
}

// login authenticates with the master and returns a JWT token.
func (c *apiClient) login(username, password string) (string, error) {
	body := fmt.Sprintf(`{"username":"%s","password":"%s"}`, username, password)
	req, err := http.NewRequest("POST", c.baseURL+"/api/v1/auth/login", strings.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpCli.Do(req)
	if err != nil {
		return "", fmt.Errorf("login request: %w", err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("read login response: %w", err)
	}

	var result struct {
		Status struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"status"`
		Token string `json:"token"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return "", fmt.Errorf("parse login response: %w (body: %s)", err, string(raw))
	}
	if result.Status.Code != "RESP_CODE_SUCCESS" {
		return "", fmt.Errorf("login failed: %s", result.Status.Message)
	}
	if result.Token == "" {
		return "", fmt.Errorf("login: empty token in response")
	}
	return result.Token, nil
}

// getClientConfig fetches the frpc config JSON for the given client ID.
// The config field is a JSON string (not base64-encoded, since the proto
// field is `string config` not `bytes config`).
func (c *apiClient) getClientConfig(clientID string) ([]byte, error) {
	body := fmt.Sprintf(`{"clientId":"%s"}`, clientID)
	req, err := http.NewRequest("POST", c.baseURL+"/api/v1/client/get", strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", c.token)

	resp, err := c.httpCli.Do(req)
	if err != nil {
		return nil, fmt.Errorf("get client request: %w", err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read client response: %w", err)
	}

	var result struct {
		Status struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"status"`
		Client *struct {
			ID         string `json:"id"`
			Config     string `json:"config"`
			ServerID   string `json:"serverId"`
			FrpsURL    string `json:"frpsUrl"`
			Stopped    bool   `json:"stopped"`
		} `json:"client"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return nil, fmt.Errorf("parse client response: %w (body: %s)", err, string(raw))
	}
	if result.Status.Code != "RESP_CODE_SUCCESS" {
		return nil, fmt.Errorf("get client: %s", result.Status.Message)
	}
	if result.Client == nil {
		return nil, fmt.Errorf("get client: nil client in response")
	}
	if result.Client.Stopped {
		return nil, fmt.Errorf("client %s is stopped", clientID)
	}
	if result.Client.Config == "" {
		return nil, fmt.Errorf("client %s has no config", clientID)
	}

	// Config is a JSON string containing the frpc config
	return []byte(result.Client.Config), nil
}
