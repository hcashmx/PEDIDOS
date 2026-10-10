"""Windows UAT controller. Temporary user via ERP API; passwords stay in memory.

Start with --interactive and use commands printed by the controller. UI input is
sent only to the process launched here. SQLite and evidence use an isolated directory.
"""
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
SYNC = ROOT.parents[1] / "Backend/effyFlow"
LOCAL = dict(line.split("=", 1) for line in (SYNC / ".env.local").read_text(encoding="utf-8-sig").splitlines()
             if "=" in line and not line.strip().startswith("#"))
ERP = LOCAL["ERP_BASE_URL"].rstrip("/")
user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
user32.GetForegroundWindow.restype = wintypes.HWND


def api(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with urlopen(Request(ERP + path, method=method, headers=headers,
                             data=None if body is None else json.dumps(body).encode()), timeout=30) as response:
            data = response.read()
            return json.loads(data) if data else None
    except HTTPError as error:
        raise RuntimeError(f"ERP {method} {path}: HTTP {error.code}") from None


def key(vk):
    user32.keybd_event(vk, 0, 0, 0)
    time.sleep(.03)
    user32.keybd_event(vk, 0, 2, 0)
    time.sleep(.03)


def clear_field():
    subprocess.run(["powershell", "-NoProfile", "-Command",
        "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('^a{BACKSPACE}')"],
        check=True, creationflags=subprocess.CREATE_NO_WINDOW, stdout=subprocess.DEVNULL)
    time.sleep(.2)


def text_input(value):
    # Unicode input avoids keyboard layout/Caps Lock changes to fixture credentials.
    class Keyboard(ctypes.Structure):
        _fields_ = [("vk", wintypes.WORD), ("scan", wintypes.WORD), ("flags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]
    class Mouse(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("data", wintypes.DWORD),
                    ("flags", wintypes.DWORD), ("time", wintypes.DWORD), ("extra", ctypes.c_size_t)]
    class Hardware(ctypes.Structure):
        _fields_ = [("msg", wintypes.DWORD), ("low", wintypes.WORD), ("high", wintypes.WORD)]
    class InputData(ctypes.Union):
        _fields_ = [("keyboard", Keyboard), ("mouse", Mouse), ("hardware", Hardware)]
    class Input(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("data", InputData)]
    encoded = value.encode("utf-16-le")
    for index in range(0, len(encoded), 2):
        unit = int.from_bytes(encoded[index:index+2], "little")
        events = (Input * 2)(Input(1, InputData(keyboard=Keyboard(0, unit, 4, 0, 0))),
                            Input(1, InputData(keyboard=Keyboard(0, unit, 6, 0, 0))))
        assert user32.SendInput(2, events, ctypes.sizeof(Input)) == 2
        time.sleep(.01)
    time.sleep(.4)


def main():
    token = api("POST", "/api/auth/login", {"usernameOrEmail": LOCAL["ERP_USERNAME"], "password": LOCAL["ERP_PASSWORD"]})["token"]
    roles = api("GET", "/api/roles?page=0&size=100", token=token)
    if isinstance(roles, dict):
        roles = roles.get("content", roles.get("data", []))
    role_id = next(role["id"] for role in roles if role["name"] == "VENDEDOR")
    username = "pedauth02_" + secrets.token_hex(4)
    password = "UatAuth02" + secrets.token_hex(12)
    original_password = password
    created = api("POST", "/api/usuarios", {"username": username, "email": username + "@example.invalid",
        "password": password, "idRol": role_id, "fullName": "PED-AUTH-OFFLINE-02 UAT", "active": True,
        "almacenIds": [], "tipoCuenta": "INTERACTIVA", "tipoAccesoHorario": "SIEMPRE"}, token)
    user_id = created["id"]
    directory = Path(tempfile.mkdtemp(prefix="PED-AUTH-OFFLINE-02-"))
    database = directory / "EffyFlow/effy.db"
    environment = os.environ.copy()
    environment.update({"JAVA_HOME": "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot",
        "LOCALAPPDATA": str(directory), "PED_AUTH_UAT_DB": str(database), "PED_AUTH_UAT_USERNAME": username,
        "PED_AUTH_UAT_PASSWORD": password, "PED_AUTH_UAT_OLD_PASSWORD": original_password,
        "SYNC_LOCAL_CONFIG": str(SYNC / ".env.local"), "ERP_TEST_SCENARIOS_FILE": str(SYNC / "target/ped-com-uat-scenarios.json")})
    evidence = {"directory": str(directory), "database": str(database), "userId": user_id, "username": username, "phases": []}
    (directory / "evidence.json").write_text(json.dumps(evidence, indent=2))
    app = None
    handle = None

    def phase(name):
        environment["PED_AUTH_UAT_PHASE"] = name
        log = directory / (name + ".log")
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run([str(ROOT / "gradlew.bat"), ":composeApp:jvmTest", "--rerun", "--tests",
                "*OfflineAuthenticationLocalUatTest", "--console=plain"], cwd=ROOT, env=environment,
                stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        assert result.returncode == 0, f"Phase {name} failed; inspect {log}"
        evidence["phases"].append(name)
        (directory / "evidence.json").write_text(json.dumps(evidence, indent=2))
        print(f"PHASE_PASS {name}; log={log}", flush=True)

    def focus():
        assert app and app.poll() is None and handle
        user32.ShowWindow(handle, 9)
        foreground = user32.GetForegroundWindow()
        foreground_thread = user32.GetWindowThreadProcessId(foreground, None)
        current_thread = ctypes.windll.kernel32.GetCurrentThreadId()
        user32.AttachThreadInput(current_thread, foreground_thread, True)
        key(0x12)
        user32.BringWindowToTop(handle)
        user32.SetForegroundWindow(handle)
        user32.SetFocus(handle)
        user32.AttachThreadInput(current_thread, foreground_thread, False)
        time.sleep(.5)
        assert user32.GetForegroundWindow() == handle, "Refuse input to another window"

    def click(x, y):
        focus()
        rectangle = wintypes.RECT()
        user32.GetWindowRect(handle, ctypes.byref(rectangle))
        user32.SetCursorPos(rectangle.left + x, rectangle.top + y)
        user32.mouse_event(2, 0, 0, 0, 0)
        user32.mouse_event(4, 0, 0, 0, 0)
        time.sleep(.5)

    def capture(label):
        focus()
        rectangle = wintypes.RECT()
        user32.GetWindowRect(handle, ctypes.byref(rectangle))
        destination = directory / (label + ".png")
        quote = "'" + str(destination).replace("'", "''") + "'"
        command = ("Add-Type -AssemblyName System.Drawing; "
            f"$b=New-Object System.Drawing.Bitmap({rectangle.right-rectangle.left},{rectangle.bottom-rectangle.top}); "
            "$g=[System.Drawing.Graphics]::FromImage($b); "
            f"$g.CopyFromScreen({rectangle.left},{rectangle.top},0,0,$b.Size); "
            f"$b.Save({quote},[System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $b.Dispose()")
        subprocess.run(["powershell", "-NoProfile", "-Command", command], check=True, creationflags=subprocess.CREATE_NO_WINDOW)
        print(f"SCREENSHOT {destination}", flush=True)

    try:
        phase("prepare")
        print(f"UAT_DIRECTORY={directory}; USER_ID={user_id}; USERNAME={username}", flush=True)
        print("Commands: open, capture LABEL, click X Y, fill X Y, wrong X Y, close, phase NAME, reset, disable, quit", flush=True)
        while True:
            parts = input().split()
            if not parts:
                continue
            command = parts[0]
            if command == "open":
                executable = ROOT / "composeApp/build/compose/binaries/main/app/EffyFlow/EffyFlow.exe"
                app = subprocess.Popen([str(executable)], env=environment, creationflags=subprocess.CREATE_NO_WINDOW)
                # jpackage Windows launcher creates a child process which owns the window.
                time.sleep(1)
                children = subprocess.check_output(["powershell", "-NoProfile", "-Command",
                    f"Get-CimInstance Win32_Process -Filter 'ParentProcessId={app.pid}' | Select-Object -ExpandProperty ProcessId"],
                    creationflags=subprocess.CREATE_NO_WINDOW, text=True)
                window_pids = {app.pid} | {int(pid) for pid in children.split()}
                for _ in range(80):
                    handles = []
                    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
                    def callback(window, _):
                        pid = wintypes.DWORD()
                        user32.GetWindowThreadProcessId(window, ctypes.byref(pid))
                        if pid.value in window_pids and user32.IsWindowVisible(window):
                            title = ctypes.create_unicode_buffer(256)
                            user32.GetWindowTextW(window, title, 256)
                            if title.value == "EffyFlow":
                                handles.append(window)
                        return True
                    user32.EnumWindows(callback_type(callback), 0)
                    if handles:
                        handle = handles[0]; break
                    assert app.poll() is None, "Desktop app exited"
                    time.sleep(.25)
                assert handle
                time.sleep(2)
                print(f"APP_OPEN pid={app.pid}", flush=True)
                capture("opened")
            elif command == "capture":
                capture(parts[1])
            elif command == "click":
                click(int(parts[1]), int(parts[2]))
            elif command in {"fill", "wrong"}:
                click(int(parts[1]), int(parts[2]))
                clear_field()
                text_input(username)
                key(0x09)
                clear_field()
                text_input(password if command == "fill" else "deliberatelywrong")
                print("UI_CREDENTIALS_ENTERED", flush=True)
            elif command == "close":
                if app and app.poll() is None:
                    user32.PostMessageW(handle, 0x10, 0, 0)
                    app.wait(timeout=15)
                app = None; handle = None
                print("APP_CLOSED", flush=True)
            elif command == "phase":
                assert app is None, "Close app before isolated SQLite verification"
                phase(parts[1])
            elif command == "reset":
                token = api("POST", "/api/auth/login", {"usernameOrEmail": LOCAL["ERP_USERNAME"], "password": LOCAL["ERP_PASSWORD"]})["token"]
                password = "UatAuth02New" + secrets.token_hex(12)
                api("PUT", f"/api/usuarios/{user_id}", {"username": username, "email": created["email"],
                    "password": password, "idRol": role_id, "fullName": created["fullName"], "active": True,
                    "almacenIds": [], "tipoCuenta": "INTERACTIVA", "tipoAccesoHorario": "SIEMPRE"}, token)
                environment["PED_AUTH_UAT_PASSWORD"] = password
                print("ERP_PASSWORD_RESET_VIA_API", flush=True)
            elif command == "disable":
                token = api("POST", "/api/auth/login", {"usernameOrEmail": LOCAL["ERP_USERNAME"], "password": LOCAL["ERP_PASSWORD"]})["token"]
                api("PATCH", f"/api/usuarios/{user_id}/toggle-activo", token=token)
                print("ERP_USER_DISABLED_VIA_API", flush=True)
            elif command == "quit":
                break
            else:
                raise ValueError("Unknown command")
    finally:
        if app and app.poll() is None:
            if handle:
                user32.PostMessageW(handle, 0x10, 0, 0)
            try:
                app.wait(timeout=15)
            except subprocess.TimeoutExpired:
                subprocess.run(["taskkill", "/PID", str(app.pid), "/T", "/F"], creationflags=subprocess.CREATE_NO_WINDOW,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Disable only our fixture account; never delete historical orders or users.
        token = api("POST", "/api/auth/login", {"usernameOrEmail": LOCAL["ERP_USERNAME"], "password": LOCAL["ERP_PASSWORD"]})["token"]
        if api("GET", f"/api/usuarios/{user_id}", token=token)["active"]:
            api("PATCH", f"/api/usuarios/{user_id}/toggle-activo", token=token)
        print(f"UAT_FIXTURE_DISABLED id={user_id}; evidence={directory / 'evidence.json'}", flush=True)


if __name__ == "__main__":
    assert sys.platform == "win32", "Windows desktop UAT only"
    main()
