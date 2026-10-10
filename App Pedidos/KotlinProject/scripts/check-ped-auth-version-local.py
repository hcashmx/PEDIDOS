"""Real ERP API password reset/disable, then Kotlin SQLite verification. No SQL writes to ERP."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ped_auth_uat", ROOT / "scripts/run-ped-auth-offline-02-uat.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def admin_token():
    return helper.api("POST", "/api/auth/login", {"usernameOrEmail": helper.LOCAL["ERP_USERNAME"],
        "password": helper.LOCAL["ERP_PASSWORD"]})["token"]


def main():
    database = Path(sys.argv[1]).resolve(strict=True)
    directory = database.parent.parent
    assert directory.name.startswith("PED-AUTH-OFFLINE-02-"), "Only task-owned UAT SQLite is allowed"
    token = admin_token()
    roles = helper.api("GET", "/api/roles?page=0&size=100", token=token)
    if isinstance(roles, dict):
        roles = roles.get("content", roles.get("data", []))
    role_id = next(role["id"] for role in roles if role["name"] == "VENDEDOR")
    username = "pedauth02version_" + secrets.token_hex(4)
    old_password = secrets.token_urlsafe(24)
    new_password = secrets.token_urlsafe(24)
    created = helper.api("POST", "/api/usuarios", {"username": username, "email": username + "@example.invalid",
        "password": old_password, "idRol": role_id, "fullName": "PED-AUTH-OFFLINE-02 version UAT", "active": True,
        "almacenIds": [], "tipoCuenta": "INTERACTIVA", "tipoAccesoHorario": "SIEMPRE"}, token)
    user_id = created["id"]
    environment = os.environ.copy()
    environment.update({"JAVA_HOME": "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot",
        "PED_AUTH_UAT_DB": str(database), "PED_AUTH_UAT_USERNAME": username,
        "PED_AUTH_UAT_PASSWORD": old_password, "PED_AUTH_UAT_OLD_PASSWORD": old_password,
        "SYNC_LOCAL_CONFIG": str(helper.SYNC / ".env.local")})
    evidence = {"userId": user_id, "username": username, "phases": []}

    def phase(name):
        environment["PED_AUTH_UAT_PHASE"] = name
        log = directory / ("credential_" + name + ".log")
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run([str(ROOT / "gradlew.bat"), ":composeApp:jvmTest", "--rerun", "--tests",
                "*OfflineAuthenticationLocalUatTest", "--console=plain"], cwd=ROOT, env=environment,
                stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        assert result.returncode == 0, f"Phase {name} failed; inspect {log}"
        evidence["phases"].append(name)
        (directory / "credential-evidence.json").write_text(json.dumps(evidence, indent=2))
        print(f"PHASE_PASS {name}; userId={user_id}; log={log}", flush=True)

    try:
        phase("enroll")
        token = admin_token()
        helper.api("PUT", f"/api/usuarios/{user_id}", {"username": username, "email": created["email"],
            "password": new_password, "idRol": role_id, "fullName": created["fullName"], "active": True,
            "almacenIds": [], "tipoCuenta": "INTERACTIVA", "tipoAccesoHorario": "SIEMPRE"}, token)
        environment["PED_AUTH_UAT_PASSWORD"] = new_password
        phase("version")
        token = admin_token()
        helper.api("PATCH", f"/api/usuarios/{user_id}/toggle-activo", token=token)
        phase("disabled")
    finally:
        token = admin_token()
        if helper.api("GET", f"/api/usuarios/{user_id}", token=token)["active"]:
            helper.api("PATCH", f"/api/usuarios/{user_id}/toggle-activo", token=token)
        print(f"UAT_FIXTURE_DISABLED id={user_id}", flush=True)


if __name__ == "__main__":
    main()
