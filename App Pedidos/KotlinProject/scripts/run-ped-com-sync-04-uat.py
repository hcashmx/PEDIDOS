"""Run opt-in Kotlin UAT against existing local Sync/ERP; no business SQL writes.

Reads existing Sync .env.local and externally prepared PED-COM-SYNC-03 fixtures.
Creates an API-only temporary Sync-compatible account and restores task-owned
promotion/volume activity. Never changes preexisting users, roles or orders.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SYNC_DIR = Path(os.environ.get("SYNC_PROJECT_DIR", str(ROOT.parents[1] / "Backend/effyFlow")))
LOCAL = dict(line.split("=", 1) for line in (SYNC_DIR / ".env.local").read_text(encoding="utf-8-sig").splitlines()
             if "=" in line and not line.strip().startswith("#"))
BASE = LOCAL["ERP_BASE_URL"].rstrip("/")
token = None


def api(method, path, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            return json.loads(data) if data else None
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"ERP UAT API {method} {path}: HTTP {exc.code}") from None


def login():
    global token
    token = None
    token = api("POST", "/api/auth/login", {"usernameOrEmail": LOCAL["ERP_USERNAME"], "password": LOCAL["ERP_PASSWORD"]})["token"]


def main():
    login()
    catalog_path = Path(os.environ.get("ERP_TEST_CATALOG_FILE", str(SYNC_DIR / "target/ped-com-uat-catalog.json")))
    catalog = json.loads(catalog_path.read_text(encoding="utf-8-sig"))
    restored = []
    role_id = user_id = None
    exit_code = 1
    cleanup_errors = []
    try:
        for path in (f'/api/promociones-temporada/{catalog["promocionId"]}', f'/api/precios-volumen/{catalog["volumenId"]}'):
            state = api("GET", path)
            active = state["activo"] if "activo" in state else state["activa"]
            if not active:
                api("PATCH", path + "/activar")
                restored.append(path)
        # The fixture role has no ERP permissions. Sync uses its legacy ADMIN enum
        # only for the authenticated mobile side; ERP authority uses .env.local.
        role_id = api("POST", "/api/roles", {"name": "ADMIN", "guardName": "ped-com-sync04-uat"})["id"]
        unique = "ped04_" + secrets.token_hex(5)
        email = unique + "@example.invalid"
        password = secrets.token_urlsafe(24)
        user_id = api("POST", "/api/usuarios", {"username": unique, "email": email, "password": password,
            "idRol": role_id, "fullName": "PED-COM-SYNC-04 UAT temporal", "active": True,
            "almacenIds": [], "tipoCuenta": "TECNICA", "tipoAccesoHorario": "SIEMPRE"})["id"]
        evidence = Path(os.environ["TEMP"]) / "PED-COM-SYNC-04/evidence.json"
        evidence.parent.mkdir(parents=True, exist_ok=True)
        test_env = os.environ.copy()
        test_env.update({"PED_COM_SYNC_04_ENABLED": "true", "SYNC_LOCAL_CONFIG": str(SYNC_DIR / ".env.local"),
            "SYNC_TEST_BASE_URL": os.environ.get("SYNC_TEST_BASE_URL", "http://localhost:8082"),
            "SYNC_TEST_EMAIL": email, "SYNC_TEST_PASSWORD": password,
            "ERP_TEST_SCENARIOS_FILE": os.environ.get("ERP_TEST_SCENARIOS_FILE", str(SYNC_DIR / "target/ped-com-uat-scenarios.json")),
            "ERP_TEST_DB_URL": os.environ.get("ERP_TEST_DB_URL", "jdbc:postgresql://localhost:5432/prueba"),
            "PED_COM_SYNC_04_EVIDENCE": str(evidence),
            "POSTGRES_JDBC_JAR": os.environ.get("POSTGRES_JDBC_JAR", str(Path.home() / ".m2/repository/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar"))})
        assert test_env.get("DB_PASSWORD") and test_env.get("DB_USERNAME"), "Supply existing UAT DB environment"
        exit_code = subprocess.run([str(ROOT / "gradlew.bat"), ":composeApp:jvmTest", "--rerun", "--tests",
            "*PedidoComercialLocalIntegrationTest", "--console=plain"], cwd=ROOT, env=test_env).returncode
        print(f"Kotlin real UAT exit={exit_code}; evidence={evidence}", flush=True)
    finally:
        login()
        actions = []
        if user_id:
            actions.append(("PATCH", f"/api/usuarios/{user_id}/toggle-activo", None))
        if role_id:
            actions.append(("PUT", f"/api/roles/{role_id}", {"name": "PED_COM_SYNC04_INACTIVE_" + str(role_id), "guardName": "ped-com-sync04-uat"}))
        actions.extend(("PATCH", path + "/desactivar", None) for path in restored)
        for method, path, body in actions:
            try:
                api(method, path, body)
                print(f"UAT fixture restored: {method} {path}", flush=True)
            except Exception as exc:
                cleanup_errors.append(str(exc))
        if cleanup_errors:
            raise RuntimeError("UAT cleanup requires review: " + "; ".join(cleanup_errors))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
