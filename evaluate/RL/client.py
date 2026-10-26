"""Minimal RWX Gymnasium TCP client.

Java server uses newline-delimited JSON, one response per request:

  {"cmd":"describe","case":"CASE1"}
    -> {"obs_dim":20,"n_actions":9}
  {"cmd":"reset","case":"CASE1","seed":null,"options":{}}
    -> {"observation":[...],"info":{}}
  {"cmd":"step","action":3}
    -> {"observation":[...],"reward":0.0,
        "terminated":false,"truncated":false,"info":{}}

The server should apply the action, advance the game, and sample the new
observation atomically. The Java service must implement these commands.
"""

import json
import socket

import gymnasium as gym
import numpy as np
from gymnasium import spaces


class RWXEnv(gym.Env):
    metadata = {"render_modes": ["human", "none"]}

    def __init__(self, case, render_mode=None, host="127.0.0.1", port=41390):
        super().__init__()
        if render_mode not in (None, "human"):
            raise ValueError("render_mode must be None or 'human'")
        self.case = case
        self.render_mode = render_mode
        self.socket = socket.create_connection((host, port), timeout=10)
        self.socket.settimeout(120)
        self.reader = self.socket.makefile("r", encoding="utf-8")
        try:
            spec = self._request({"cmd": "describe", "case": case,
                                  "render_mode": render_mode})
            self.action_space = spaces.Discrete(int(spec["n_actions"]))
            self.observation_space = spaces.Box(
                low=-np.inf,
                high=np.inf,
                shape=(int(spec["obs_dim"]),),
                dtype=np.float32,
            )
        except Exception:
            self.close()
            raise

    def _request(self, message):
        self.socket.sendall((json.dumps(message) + "\n").encode("utf-8"))
        line = self.reader.readline()
        if not line:
            raise ConnectionError("RWX Java server closed the connection")
        result = json.loads(line)
        if "error" in result:
            raise RuntimeError(str(result["error"]))
        return result

    def _obs(self, response):
        obs = np.asarray(response["observation"], dtype=np.float32)
        if not self.observation_space.contains(obs):
            raise ValueError(f"Invalid observation shape or values: {obs.shape}")
        return obs

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        response = self._request({
            "cmd": "reset", "case": self.case, "seed": seed,
            "options": options or {},
        })
        return self._obs(response), response.get("info", {})

    def step(self, action):
        action = int(action)
        if not self.action_space.contains(action):
            raise ValueError(f"Invalid action: {action}")
        response = self._request({"cmd": "step", "action": action})
        return (
            self._obs(response),
            float(response["reward"]),
            bool(response["terminated"]),
            bool(response["truncated"]),
            response.get("info", {}),
        )

    def close(self):
        if getattr(self, "reader", None) is not None:
            self.reader.close()
            self.reader = None
        if getattr(self, "socket", None) is not None:
            self.socket.close()
            self.socket = None


def make(case, render_mode=None, **kwargs):
    return RWXEnv(case, render_mode=render_mode, **kwargs)