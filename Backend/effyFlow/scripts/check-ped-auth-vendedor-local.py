"""Read-only local UAT: use existing .env.local credentials, never print secrets."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = "http://localhost:8082"
config = {}
for line in (Path(__file__).resolve().parents[1] / ".env.local").read_text(encoding="utf-8-sig").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        config[key.strip()] = value.strip().strip("\"'")


def request(path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, {}


status, login = request("/api/auth/login", {
    "email": config["ERP_USERNAME"], "password": config["ERP_PASSWORD"]})
assert status == 200, f"Login HTTP {status}"
assert login["roles"] == "VENDEDOR", "Fixture must retain its original VENDEDOR role"
print("Login VENDEDOR: HTTP 200")
status, refreshed = request("/api/auth/refresh", {"refreshToken": login["refreshToken"]})
assert status == 200, f"Refresh HTTP {status}"
token = refreshed["accessToken"]
print("Refresh VENDEDOR: HTTP 200")
status, catalog = request("/api/sync/catalogos/usuarios?page=0&size=500", token=token)
assert status == 200, f"Catalog HTTP {status}"
assert catalog["data"], "Expected existing users"
assert all(not ({"password", "pass", "verifier", "passwordHash"} & set(user)) for user in catalog["data"])
print("Users catalog: HTTP 200; no credential verifier (offline provisioning unavailable)")
status, _ = request("/api/sync/pedidos/estados?afterId=0&limit=1", token=token)
assert status == 200, f"State feed HTTP {status}"
print("PED-SYNC-R1 feed: HTTP 200")
status, _ = request("/api/auth/login", {
    "email": config["ERP_USERNAME"], "password": "ped-auth-deliberately-wrong"})
assert status == 401, f"Wrong password HTTP {status}"
print("Wrong password: HTTP 401")
