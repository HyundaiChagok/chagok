#!/usr/bin/env python3
"""차곡 시연 콘솔. PT 때 옆 창에서 돌린다.

에뮬레이터의 차 값을 Virtual Console(8088 포트)로 주입한다. 앱은 Pleos SDK로 이 값을 읽는다.
실차 값이 아니라 주입한 값이고, 각 장면이 무엇을 주입하는지 화면에 그대로 찍는다.

    python scripts/demo-console.py            번호를 눌러 장면을 실행한다
    python scripts/demo-console.py 0 1        장면을 차례로 실행하고 끝낸다
    python scripts/demo-console.py p          시연 전 점검만 한다(아무것도 바꾸지 않는다)

표준 라이브러리만 쓴다. adb는 ADB 환경 변수, ANDROID_HOME, %LOCALAPPDATA%\\Android\\Sdk, PATH 순서로 찾는다.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CONSOLE = "http://127.0.0.1:8088"
CLIENT = "demo-console"
PACKAGE = "app.chagok"
USER = "10"  # 에뮬레이터의 앱은 사용자 10으로 돈다.
TIME_ZONE = "Asia/Seoul"

# 시연 이야기의 값. 인수 때 82%, 23,410km에서 시작해 312km를 달리고 61%로 돌아온다.
START_SOC = 82
START_KM = 23_410
DRIVEN_KM = 312
DRIVEN_SOC = 61
TO_RETURN_KM = 26  # 내비가 계산한 반납 지점까지의 거리와 도착 예상 배터리. 앱은 쓰지 않는다.
ARRIVAL_SOC = 56
CHARGE_STEP = 2  # 충전 장면에서 한 번에 올리는 %p와 간격(초). 앱의 게이지가 따라 올라가는 것을 보여 주는 속도다.
CHARGE_EVERY = 1.0
CAPACITY_KWH = 77.4  # 에뮬레이터의 배터리 용량(EV_BATTERY_CAPACITY)
KM_PER_PERCENT = 4.4  # 왼쪽 계기 화면의 남은 거리가 배터리와 어긋나 보이지 않게 맞추는 값이다. 앱은 쓰지 않는다.
LOW_TIRE_KPA = 120
NORMAL_TIRE_KPA = 230
FRONT_LEFT_WHEEL = 1114113  # Virtual Console의 앞 왼쪽 바퀴 번호(GET /vehicle/catalog)
WHEELS = {FRONT_LEFT_WHEEL: "앞 왼쪽", 1114191: "앞 오른쪽", 1179649: "뒤 왼쪽", 1179727: "뒤 오른쪽"}

WINDOW_FIELDS = {"front_left": "window_fl", "front_right": "window_fr", "rear_left": "window_rl", "rear_right": "window_rr"}
DOOR_FIELDS = {"front_left": "door_fl", "front_right": "door_fr", "rear_left": "door_rl", "rear_right": "door_rr"}
SIDE_NAMES = {"front_left": "운전석", "front_right": "조수석", "rear_left": "뒷좌석 왼쪽", "rear_right": "뒷좌석 오른쪽"}
SEAT_NAMES = {"passenger": "조수석", "rear_left": "뒷좌석 왼쪽", "rear_right": "뒷좌석 오른쪽"}

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT_WEB = "https://rosieoh.github.io/chagok-report/r/"
NAVI_SERVICE = "ai.pleos.playground.service.navi"
# 공개 웹이 이 앱의 기록을 제대로 보여 주는지 가늠하는 키. 옛 판에는 없어서 항목 이름 대신 키가 그대로 보인다.
NEW_WEB_KEYS = ["trunkOpen", "chargePortOpen", "recordedBy", "noVin"]
REPLY_PCM = os.path.join(REPO, "scripts", "reply-test-ne.pcm")  # Windows 음성(Microsoft Heami)으로 만든 '네'. 16kHz 16비트 모노.
REPLY_FILE = "files/reply-test.pcm"
SHORT_UPTIME_SECONDS = 300  # 부팅 뒤 첫 음성은 25초까지 걸린다. 그동안은 차도 바쁘다.
LOAD_WARN, LOAD_FAIL = 8.0, 15.0  # load 15에서 ANR이 난 적이 있다.
FREE_MB_WARN, FREE_MB_FAIL = 1024, 400


def adb_path():
    found = os.environ.get("ADB")
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(os.path.join(local, "Android", "Sdk"))
    for root in filter(None, roots):
        for name in ("adb.exe", "adb"):
            path = os.path.join(root, "platform-tools", name)
            if os.path.exists(path):
                return path
    return shutil.which("adb") or "adb"


ADB = adb_path()


def adb(*args, show=True):
    """adb를 부르고 출력을 돌려준다. 안 되면 사유를 찍고 None이다."""
    if show:
        print("  $ adb " + " ".join(args))
    try:
        done = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f"  ! adb 실패: {e}")
        return None
    if done.returncode != 0:
        print(f"  ! adb 실패: {(done.stderr or done.stdout).strip()}")
        return None
    return done.stdout.strip()


def request(method, path, **query):
    url = CONSOLE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    # 앱을 막 설치했거나 부팅 직후면 에뮬레이터가 바빠 응답이 느리다.
    with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=20) as response:
        return response.read().decode("utf-8", "replace")


def inject(what, path, quiet=False, **query):
    """값 하나를 주입하고 무엇을 주입했는지 찍는다."""
    shown = path + ("?" + urllib.parse.urlencode(query) if query else "")
    try:
        request("POST", path, **query)
        if not quiet:
            print(f"  주입  {what:<26} POST {shown}")
    except (urllib.error.URLError, OSError) as e:
        print(f"  실패  {what:<26} POST {shown}  ({e})")


def state():
    return json.loads(request("GET", "/vehicle/state"))


def keep_session():
    """Virtual Console은 세션이 살아 있어야 쓰기를 받는다(1초마다)."""
    warned = False
    while True:
        try:
            request("POST", "/browser/session", client=CLIENT)
            warned = False
        except (urllib.error.URLError, OSError) as e:
            if not warned:
                print(f"\n  ! Virtual Console 세션을 유지하지 못했습니다: {e}")
            warned = True
        time.sleep(1)


def set_soc(percent, quiet=False):
    inject(f"배터리 {percent}%", "/vehicle/ev-battery", quiet=quiet, field="soc", value=percent)
    # 내비는 SOC가 아니라 배터리 에너지(EV_BATTERY_LEVEL)로 도착 예상 배터리를 계산한다. 함께 맞추지 않으면 앱과 내비의 배터리가 어긋난다.
    kwh = round(percent * CAPACITY_KWH / 100, 1)
    inject(f"배터리 에너지 {kwh}kWh(내비 계산용)", "/vehicle/ev-battery", quiet=quiet, field="battery_level", value=kwh)
    inject(f"남은 거리 {round(percent * KM_PER_PERCENT)}km(계기 표시)", "/vehicle/odometer", quiet=quiet, remaining_range_m=round(percent * KM_PER_PERCENT) * 1000)


def set_gear(name, label):
    inject(f"기어 {label}", "/vehicle/gear", gear=name)


def lit_telltales(current):
    actual = current.get("telltales", {}).get("actual") or {}
    return [name for name, value in actual.items() if isinstance(value, dict) and value.get("state") not in (None, "Off")]


# ----- 장면 -----


def scene_setup():
    """에뮬레이터 초기 설정: 포트, 시간대, 알림 권한"""
    adb("forward", "tcp:8088", "tcp:8088")
    # 리포트 시각은 서울 시간이다. 상태 표시줄 시계를 맞춰 화면끼리 어긋나지 않게 한다. 시각 자체는 바꾸지 않는다.
    adb("shell", "cmd", "alarm", "set-timezone", TIME_ZONE)
    print("  에뮬레이터 시각: " + (adb("shell", "date", show=False) or "읽지 못함"))
    # 대여 중 기록 서비스의 알림이 보이게 한다. 앱이 없으면 실패하고 넘어간다.
    adb("shell", "pm", "grant", "--user", USER, PACKAGE, "android.permission.POST_NOTIFICATIONS")


def scene_reset():
    """시작 상태로 초기화: 모두 닫힘, 배터리 82%, 23,410km, P단"""
    current = state()
    set_gear("Park", "P")
    for side, field in DOOR_FIELDS.items():
        inject(f"{SIDE_NAMES[side]} 문 닫기", "/vehicle/body", **{field: "false"})
    for side, field in WINDOW_FIELDS.items():
        inject(f"{SIDE_NAMES[side]} 창문 닫기", "/vehicle/body", **{field: 0})
    inject("트렁크 닫기", "/vehicle/body", trunk="false")
    inject("실내등 끄기", "/vehicle/body", cabin_light="false")
    inject("전조등·미등 끄기", "/vehicle/lights", feature="headlamp_mode", value="Off")
    for seat, name in SEAT_NAMES.items():
        inject(f"{name} 좌석 비우기", "/vehicle/seat", area=seat, kind="occupied", value="false")
    inject("충전구 닫기", "/vehicle/ev-battery", field="charge_port_open", value="false")
    inject("충전 스위치 끄기", "/vehicle/ev-battery", field="charge_switch", value="false")
    # 넣어 둔 시험 음성이 다음 질문에 저절로 답하지 않게 지운다. 릴리스 빌드에서는 실패하고 넘어간다.
    quiet_shell("run-as", PACKAGE, "--user", USER, "rm", "-f", REPLY_FILE)
    set_soc(START_SOC)
    km = current.get("odometer", {}).get("traveled_distance_km", 0)
    if km <= START_KM:
        inject(f"주행거리 {START_KM:,}km", "/vehicle/odometer", traveled_distance_km=START_KM)
    else:
        # Virtual Console은 주행거리를 줄이지 않는다(traveled_distance_must_not_decrease). 리포트는 차이만 보므로 이야기는 그대로다.
        print(f"  [참고] 주행거리는 줄일 수 없어 {km:,.0f}km에서 시작합니다. {START_KM:,}km로 맞추려면 에뮬레이터를 콜드 부트하세요.")
    for name in lit_telltales(current):
        inject(f"경고등 {name} 끄기", "/vehicle/telltales/simulator", type=name, state="Off", color="Red")
    for wheel, name in WHEELS.items():
        inject(f"{name} 타이어 {NORMAL_TIRE_KPA}kPa", "/vehicle/tire-pressure", area=wheel, pressure_kpa=NORMAL_TIRE_KPA)


def scene_drive():
    """주행: 기어 D, 주행거리 +312km, 배터리 61%, 기어 P (며칠 뒤로 가정)"""
    print("  [대본] 여기서부터 며칠 뒤라고 가정합니다. 시간은 속이지 않습니다. 리포트의 인수·반납 시각은 실제 시각이라 몇 분 차이로 나옵니다.")
    print("  [참고] D단인 동안 플랫폼이 앱을 잠깐 가립니다.")
    km = state().get("odometer", {}).get("traveled_distance_km", START_KM)
    set_gear("Drive", "D")
    time.sleep(1)
    inject(f"주행거리 {km:,.0f} → {km + DRIVEN_KM:,.0f}km", "/vehicle/odometer", traveled_distance_km=km + DRIVEN_KM)
    set_soc(DRIVEN_SOC)
    time.sleep(1)
    set_gear("Park", "P")


def scene_to_return():
    """반납 지점까지 주행: 기어 D, 주행거리 +26km, 배터리 56%, 기어 P"""
    print("  [참고] 내비가 계산한 도착 예상 배터리(약 56~57%)에 맞춘 대본입니다. 차가 실제로 움직이지는 않습니다.")
    km = state().get("odometer", {}).get("traveled_distance_km", START_KM)
    set_gear("Drive", "D")
    time.sleep(1)
    inject(f"주행거리 {km:,.0f} → {km + TO_RETURN_KM:,.0f}km", "/vehicle/odometer", traveled_distance_km=km + TO_RETURN_KM)
    set_soc(ARRIVAL_SOC)
    time.sleep(1)
    set_gear("Park", "P")


def scene_miss():
    """놓친 것 만들기: 조수석 창문, 트렁크, 실내등, 뒷좌석 감지"""
    inject("조수석 창문 열기", "/vehicle/body", window_fr=100)
    inject("트렁크 열기", "/vehicle/body", trunk="true")
    inject("실내등 켜기", "/vehicle/body", cabin_light="true")
    inject("뒷좌석 왼쪽 감지", "/vehicle/seat", area="rear_left", kind="occupied", value="true")


def scene_fix_next():
    """하나씩 고치기: 누를 때마다 열린 곳을 하나 닫는다"""
    current = state()
    body = current.get("body", {})
    fixes = []
    for side, field in WINDOW_FIELDS.items():
        if (body.get("windows") or {}).get(side):
            fixes.append((f"{SIDE_NAMES[side]} 창문 닫기", "/vehicle/body", {field: 0}))
    for side, field in DOOR_FIELDS.items():
        if (body.get("doors") or {}).get(side):
            fixes.append((f"{SIDE_NAMES[side]} 문 닫기", "/vehicle/body", {field: "false"}))
    if (body.get("closures") or {}).get("trunk"):
        fixes.append(("트렁크 닫기", "/vehicle/body", {"trunk": "false"}))
    if current.get("ev_battery", {}).get("charging_door_opened"):
        fixes.append(("충전구 닫기", "/vehicle/ev-battery", {"field": "charge_port_open", "value": "false"}))
    if (body.get("cabin_light") or {}).get("on"):
        fixes.append(("실내등 끄기", "/vehicle/body", {"cabin_light": "false"}))
    if current.get("lights", {}).get("headlamp_mode") not in (None, "Off", "Auto"):
        fixes.append(("전조등·미등 끄기", "/vehicle/lights", {"feature": "headlamp_mode", "value": "Off"}))
    for seat, name in SEAT_NAMES.items():
        if (current.get("seat", {}).get("occupied") or {}).get(seat):
            fixes.append((f"{name} 좌석 비우기", "/vehicle/seat", {"area": seat, "kind": "occupied", "value": "false"}))
    if not fixes:
        print("  고칠 것이 없습니다.")
        return
    what, path, query = fixes[0]
    inject(what, path, **query)
    print(f"  남은 것 {len(fixes) - 1}가지" + ("" if len(fixes) == 1 else ": " + ", ".join(f[0] for f in fixes[1:])))


def scene_charge():
    """충전: 충전구 열고 배터리를 인수 때(82%)까지 조금씩 올린 뒤 닫는다"""
    inject("충전구 열기", "/vehicle/ev-battery", field="charge_port_open", value="true")
    inject("충전 스위치 켜기", "/vehicle/ev-battery", field="charge_switch", value="true")
    time.sleep(1.5)
    # 반납 체크 화면이면 막대가 인수 눈금까지 올라가고 금액이 함께 줄어든다.
    soc = round(state().get("ev_battery", {}).get("state_of_charge", DRIVEN_SOC))
    print(f"  배터리 {soc}% → {START_SOC}%, {CHARGE_EVERY}초마다 {CHARGE_STEP}%p씩 올립니다.")
    while soc < START_SOC:
        soc = min(soc + CHARGE_STEP, START_SOC)
        set_soc(soc, quiet=True)
        print(f"  주입  배터리 {soc}%")
        time.sleep(CHARGE_EVERY)
    time.sleep(2)
    inject("충전 스위치 끄기", "/vehicle/ev-battery", field="charge_switch", value="false")
    inject("충전구 닫기", "/vehicle/ev-battery", field="charge_port_open", value="false")


def scene_warn():
    """경고등·타이어 저압 주입: TPMS 경고등, 앞 왼쪽 120kPa"""
    inject("TPMS 경고등 켜기(빨강)", "/vehicle/telltales/simulator", type="TPMS", state="On", color="Red")
    inject(f"앞 왼쪽 타이어 {LOW_TIRE_KPA}kPa", "/vehicle/tire-pressure", area=FRONT_LEFT_WHEEL, pressure_kpa=LOW_TIRE_KPA)
    print("  [참고] 이 에뮬레이터의 차량 서비스에는 공기압 수치 속성이 없어 SDK로 읽히지 않습니다. 앱의 타이어 칸은 경고등을 근거로 합니다.")


def scene_warn_off():
    """경고등 모두 끄고 타이어 공기압 되돌리기"""
    current = state()
    names = lit_telltales(current)
    for name in names:
        inject(f"경고등 {name} 끄기", "/vehicle/telltales/simulator", type=name, state="Off", color="Red")
    if not names:
        print("  켜진 경고등이 없습니다.")
    inject(f"앞 왼쪽 타이어 {NORMAL_TIRE_KPA}kPa", "/vehicle/tire-pressure", area=FRONT_LEFT_WHEEL, pressure_kpa=NORMAL_TIRE_KPA)


def scene_door():
    """운전석 문 열기: 도착 뒤 반납 기록 없이 내리려 할 때의 안내(T-13)"""
    current = state()
    gear = current.get("gear", {}).get("actual", {}).get("value")
    if gear != "Park":
        print(f"  [참고] 지금 기어는 {gear}입니다. T-13은 P단에서만 나옵니다.")
    if (current.get("body", {}).get("doors") or {}).get("front_left"):
        inject("운전석 문 닫기(먼저 닫혀 있어야 열림을 압니다)", "/vehicle/body", door_fl="false")
        time.sleep(2)
    inject("운전석 문 열기", "/vehicle/body", door_fl="true")
    print("  [기대] 도착 재현 뒤라면 '잠깐만요. 아직 반납 기록을 남기지 않았습니다…'를 한 번 말합니다. 앱이 앞이면 자막, 내비가 앞이면 도착 알림이 함께 나옵니다.")
    print("  [참고] 앱은 문 열림을 기록하지 않습니다. 말한 뒤에는 문을 듣지 않습니다. 반납 체크에서 '문 열림'으로 보이면 4로 닫습니다.")


def scene_reply():
    """'네' 시험 음성 넣기: 마이크가 없는 에뮬레이터에서 말로 답하기를 보여 준다(디버그 빌드만)"""
    print("  [밝힘] 이것은 사람 목소리가 아니라 미리 만든 시험 음성입니다. 이 에뮬레이터에는 마이크 입력이 없습니다(virtio-snd-mic0 오류).")
    print("         실제 마이크로 말한 인식은 확인하지 못했습니다. PT에서도 그대로 말합니다.")
    flags = quiet_shell("dumpsys", "package", PACKAGE) or ""
    if "DEBUGGABLE" not in flags:
        print("  ! 릴리스 빌드에는 시험 음성 길이 없습니다. 디버그 빌드에서만 됩니다.")
        return
    if not os.path.exists(REPLY_PCM):
        print(f"  ! 시험 음성 파일이 없습니다: {REPLY_PCM}")
        return
    with open(REPLY_PCM, "rb") as pcm:
        done = subprocess.run([ADB, "exec-in", "run-as", PACKAGE, "--user", USER, "sh", "-c", f"cat > {REPLY_FILE}"], stdin=pcm, capture_output=True, timeout=30)
    if done.returncode != 0:
        print(f"  ! 넣지 못했습니다: {done.stderr.decode('utf-8', 'replace').strip()}")
        return
    print(f"  넣음  {REPLY_FILE} (네, {os.path.getsize(REPLY_PCM):,}바이트)")
    print("  [순서] 지금 넣어 두고, 마지막 항목을 고치면(4) '반납 기록을 남길까요?' 뒤에 앱이 이 소리를 듣습니다. 듣는 중 표시가 뜨고 '네'로 인식하면 반납 기록을 남깁니다.")
    print("  [참고] 한 번 들으면 앱이 지웁니다. 쓰지 않았으면 1(시작 상태)이 지웁니다.")


def scene_gear_d():
    """기어 D: 반납 체크 중이면 앱이 체크를 취소한다"""
    if (state().get("body", {}).get("closures") or {}).get("trunk"):
        print("  [참고] 트렁크가 열려 있으면 시스템이 '트렁크 열림' 경고창을 띄웁니다. 화면에서 '확인'을 누르세요.")
    set_gear("Drive", "D")
    print("  [참고] D단에서는 플랫폼이 앱을 '주행 중에는 사용할 수 없습니다'로 가립니다. 9로 P단에 두면 돌아옵니다.")


def scene_gear_p():
    """기어 P"""
    set_gear("Park", "P")


def scene_open_app():
    """앱 열기"""
    adb("shell", "am", "start", "--user", USER, "-n", f"{PACKAGE}/.MainActivity")


def gleo_note():
    print("  [참고] Gleo 음성 호출은 Pleos 스키마 승인 뒤에 활성화됩니다. 승인 전이라 Gleo가 보낼 인텐트를 adb로 보냅니다.")


def scene_gleo_check():
    """Gleo 입구: 반납 체크 시작(RENTAL_START_RETURN_CHECK)"""
    gleo_note()
    adb("shell", "am", "start", "--user", USER, "-a", "RENTAL_START_RETURN_CHECK", "-p", PACKAGE, "--es", "ACTION_PARAM", "'{}'")


def scene_gleo_ask():
    """Gleo 입구: 반납 전에 뭘 확인해야 해?(RENTAL_WHAT_TO_CHECK)"""
    gleo_note()
    adb("shell", "am", "broadcast", "--user", USER, "-a", "RENTAL_WHAT_TO_CHECK", "-p", PACKAGE, "--es", "ACTION_PARAM", "'{}'")


def scene_gleo_record():
    """Gleo 입구: 인수 기록 보여 줘(RENTAL_SHOW_RECORD)"""
    gleo_note()
    adb("shell", "am", "start", "--user", USER, "-a", "RENTAL_SHOW_RECORD", "-p", PACKAGE, "--es", "ACTION_PARAM", "'{}'")


def quiet_shell(*args):
    """찍지 않고 adb shell을 부른다. 안 되면 None이다."""
    try:
        done = subprocess.run([ADB, "shell", *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.stdout.strip() if done.returncode == 0 else None


def app_report():
    """앱이 스스로 적은 상태 한 줄을 읽는다(MainActivity.dump). 앱 화면이 없으면 None이다."""
    out = quiet_shell("dumpsys", "activity", f"{PACKAGE}/.MainActivity") or ""
    for line in out.splitlines():
        if "chagok-preflight" in line:
            return dict(pair.split("=", 1) for pair in line.split("chagok-preflight", 1)[1].split() if "=" in pair)
    return None


class Preflight:
    """통과·주의·실패를 줄마다 찍고 센다."""

    def __init__(self):
        self.counts = {"통과": 0, "주의": 0, "실패": 0}

    def mark(self, verdict, what, detail=""):
        self.counts[verdict] += 1
        print(f"  [{verdict}] {what:<14} {detail}")

    def by_limit(self, what, value, warn, fail, detail, higher_is_worse=True):
        bad = (lambda limit: value > limit) if higher_is_worse else (lambda limit: value < limit)
        self.mark("실패" if bad(fail) else "주의" if bad(warn) else "통과", what, detail)


def check_emulator(p):
    if (quiet_shell("getprop", "sys.boot_completed") or "") != "1":
        p.mark("실패", "에뮬레이터", "응답이 없거나 부팅 중입니다. 켠 뒤 다시 하세요.")
        return False
    load = float((quiet_shell("cat", "/proc/loadavg") or "99").split()[0])
    p.by_limit("부하", load, LOAD_WARN, LOAD_FAIL, f"load {load:.1f} (주의 {LOAD_WARN:.0f}, 실패 {LOAD_FAIL:.0f})")
    meminfo = quiet_shell("cat", "/proc/meminfo") or ""
    found = re.search(r"MemAvailable:\s+(\d+)", meminfo)
    free_mb = int(found.group(1)) // 1024 if found else 0
    p.by_limit("여유 메모리", free_mb, FREE_MB_WARN, FREE_MB_FAIL, f"{free_mb:,}MB (주의 {FREE_MB_WARN:,}, 실패 {FREE_MB_FAIL})", higher_is_worse=False)
    zone = quiet_shell("getprop", "persist.sys.timezone") or "읽지 못함"
    p.mark("통과" if zone == TIME_ZONE else "주의", "시간대", zone + ("" if zone == TIME_ZONE else f" (0번 장면이 {TIME_ZONE}로 맞춥니다)"))
    return True


def check_console(p):
    try:
        gear = state().get("gear", {}).get("actual", {}).get("value")
        p.mark("통과", "Virtual Console", f"8088 응답, 기어 {gear}")
    except (urllib.error.URLError, OSError, ValueError) as e:
        p.mark("실패", "Virtual Console", f"응답 없음({e}). 0번 장면이 포트를 연결합니다.")


def check_web(p):
    try:
        with urllib.request.urlopen(REPORT_WEB, timeout=10) as response:
            page = response.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        p.mark("실패", "리포트 웹", f"{REPORT_WEB} 응답 없음({e}). 고객 폰이 QR을 열지 못합니다.")
        return
    missing = [key for key in NEW_WEB_KEYS if key not in page]
    if missing:
        p.mark("주의", "리포트 웹", f"열리지만 옛 판입니다. 없는 키: {', '.join(missing)}. 재배포 전에는 항목 이름 대신 키가 보입니다.")
    else:
        p.mark("통과", "리포트 웹", f"{REPORT_WEB} 새 키 {len(NEW_WEB_KEYS)}개 있음")


def check_app(p, uptime):
    if PACKAGE not in (quiet_shell("pm", "list", "packages", "--user", USER, PACKAGE) or ""):
        p.mark("실패", "앱 설치", f"사용자 {USER}에 {PACKAGE}가 없습니다.")
        return
    package = quiet_shell("dumpsys", "package", PACKAGE) or ""
    version = re.search(r"versionName=(\S+)", package)
    updated = re.search(r"lastUpdateTime=(.+)", package)
    build = "디버그 빌드" if "DEBUGGABLE" in package else "릴리스 빌드"
    p.mark("통과", "앱 설치", f"{build}, {version.group(1) if version else '버전 모름'}, 설치 {updated.group(1).strip() if updated else '시각 모름'}")
    # 앱은 첫 물음에 마지막 값을 적고 뒤에서 차를 다시 읽는다(메인 스레드를 막지 않는다). 그래서 두 번 묻는다.
    if app_report() is None:
        p.mark("실패", "앱 화면", "떠 있지 않아 차 읽기, 음성, 대여 상태를 볼 수 없습니다. a로 열고 다시 하세요.")
        return
    time.sleep(3.5)
    report = app_report() or {}

    supported, unsupported, failed = (report.get("sdk") or "0/0/0").split("/")
    failed_names = report.get("failed", "").replace("_", " ") or "없음"
    if int(supported) == 0:
        p.mark("실패", "SDK 읽기", "값을 하나도 읽지 못했습니다. 차량 서비스를 확인하세요.")
    elif int(failed) > 0:
        p.mark("주의", "SDK 읽기", f"지원 {supported}, 미지원 {unsupported}, 실패 {failed}: {failed_names}")
    else:
        p.mark("통과", "SDK 읽기", f"지원 {supported}, 미지원 {unsupported}, 실패 0 (경고등 권한 {report.get('telltale')}, Gleo 함수 {report.get('schema')}개)")

    voice = report.get("voice")
    label = {"PLEOS_TTS": "Pleos TTS", "ANDROID_TTS": "Android TTS", "CHECKING": "확인 중", "CAPTION_ONLY": "자막만"}.get(voice, voice)
    if voice == "CAPTION_ONLY" or voice is None:
        p.mark("실패", "음성 엔진", f"{label}. 소리가 나지 않습니다.")
    elif voice == "PLEOS_TTS" and uptime >= SHORT_UPTIME_SECONDS:
        p.mark("통과", "음성 엔진", f"{label} 준비됨")
    else:
        p.mark("주의", "음성 엔진", f"{label}. 부팅 뒤 {uptime:.0f}초입니다. 첫 음성은 25초까지 걸릴 수 있으니 앱을 켜 두고 기다리세요.")

    rental_open = report.get("pickup") == "true"
    navi_pid = quiet_shell("pidof", NAVI_SERVICE)
    if report.get("navi") == "true":
        p.mark("통과", "내비", "앱이 연결됨")
    elif rental_open:
        p.mark("실패", "내비", "대여가 열려 있는데 연결되지 않았습니다. '반납하러 가기'가 보이지 않습니다.")
    elif navi_pid:
        p.mark("통과", "내비", f"서비스 실행 중(pid {navi_pid}). 앱은 대여가 열릴 때 연결합니다.")
    else:
        p.mark("주의", "내비", "서비스가 아직 떠 있지 않습니다. 인수 기록 뒤 '반납하러 가기'가 보이는지 확인하세요.")

    if report.get("state") == "IDLE" and not rental_open and report.get("damaged") == "false":
        p.mark("통과", "앱 상태", "대기 화면, 열린 대여 없음")
    else:
        p.mark("실패", "앱 상태", f"{report.get('state')}, 인수 기록 {'있음' if rental_open else '없음'}, 손상 {report.get('damaged')}. 시연 전에 닫거나 직원 화면에서 무효 처리하세요.")


def scene_preflight():
    """시연 전 점검: 부하·메모리·시간대·앱·SDK·음성·내비·리포트 웹·대기 상태(읽기만 한다)"""
    p = Preflight()
    if check_emulator(p):
        uptime = float((quiet_shell("cat", "/proc/uptime") or "0").split()[0])
        if uptime < SHORT_UPTIME_SECONDS:
            p.mark("주의", "부팅 뒤 시간", f"{uptime:.0f}초. 첫 음성이 25초까지 늦을 수 있습니다.")
        else:
            p.mark("통과", "부팅 뒤 시간", f"{uptime / 60:.0f}분")
        check_console(p)
        check_app(p, uptime)
    check_web(p)
    c = p.counts
    print(f"  == 통과 {c['통과']}, 주의 {c['주의']}, 실패 {c['실패']}" + ("  시연하지 마세요." if c["실패"] else ""))


def scene_status():
    """지금 주입된 값 보기"""
    current = state()
    body = current.get("body", {})
    ev = current.get("ev_battery", {})
    odo = current.get("odometer", {})
    lights = current.get("lights", {})

    def opened(group, names):
        return ", ".join(names.get(k, k) for k, v in (group or {}).items() if v and k in names) or "없음"

    print(f"  기어        {current.get('gear', {}).get('actual', {}).get('value')}")
    print(f"  배터리      {ev.get('state_of_charge'):.0f}% (용량 {ev.get('maximum_capacity_kwh'):.1f}kWh)")
    print(f"  주행거리    {odo.get('traveled_distance_km'):,.0f}km")
    print(f"  문 열림     {opened(body.get('doors'), SIDE_NAMES)}")
    print(f"  창문 열림   {opened(body.get('windows'), SIDE_NAMES)}")
    print(f"  트렁크      {'열림' if (body.get('closures') or {}).get('trunk') else '닫힘'}")
    print(f"  충전구      {'열림' if ev.get('charging_door_opened') else '닫힘'}")
    print(f"  실내등      {'켜짐' if (body.get('cabin_light') or {}).get('on') else '꺼짐'}")
    print(f"  전조등·미등 {lights.get('headlamp_mode')}")
    print(f"  좌석 감지   {opened(current.get('seat', {}).get('occupied'), SEAT_NAMES)}")
    print(f"  경고등      {', '.join(lit_telltales(current)) or '없음'}")
    print(f"  타이어      {current.get('tire_pressure', {}).get('pressure_kpa')} kPa (SDK로는 읽히지 않음)")


SCENES = [
    ("0", scene_setup),
    ("1", scene_reset),
    ("2", scene_drive),
    ("3", scene_miss),
    ("4", scene_fix_next),
    ("5", scene_charge),
    ("6", scene_warn),
    ("7", scene_warn_off),
    ("8", scene_gear_d),
    ("9", scene_gear_p),
    ("a", scene_open_app),
    ("b", scene_gleo_check),
    ("c", scene_gleo_ask),
    ("d", scene_gleo_record),
    ("e", scene_to_return),
    ("f", scene_door),
    ("p", scene_preflight),
    ("r", scene_reply),
    ("s", scene_status),
]

PT_ORDER = """PT 순서: p(점검) → 0 → 1 → (앱을 켜고 30초 기다림) 인수 기록 → 2 → (앱) 반납하러 가기 → e → 3 → (직원 화면) 도착 재현 → f(문 열림, 내리려 할 때의 안내) → (앱) 반납 체크 시작 → 4를 다섯 번 → (앱) 네 또는 반납 기록 → QR → 닫기
  말로 답하기를 보이려면 마지막 4를 누르기 전에 r을 누릅니다. 시험 음성이고 마이크가 없어서라고 그대로 말합니다.
  결정적 순간: 반납 체크 화면에서 5를 누르면 게이지가 인수 눈금까지 올라갑니다. 4를 누를 때마다 차 그림·목록·숫자·음성이 함께 바뀝니다. 1 다음에 6을 누르고 인수하면 리포트에 초록 방패가 나옵니다.
  경로 안내를 시작하면 내비가 화면에 뜹니다. 내비가 경로를 그리는 동안(10초쯤)에는 내비 화면을 누르지 마세요. 차가 바쁠 때 내비가 멈춘 적이 있습니다.
  Gleo 입구: b 반납 체크 시작, c 뭘 확인해야 해(소리로 답함), d 인수 기록 보기
  기어 해제 시험: 반납 체크 화면에서 8을 누르면 체크가 취소됩니다. 9로 돌아옵니다.
  대여 중 기록 시험: 2 다음에 6을 누르고 앱을 뒤로 보내도 리포트의 '대여 중 기록'에 남습니다."""


def menu():
    print()
    for key, scene in SCENES:
        print(f"  {key}  {scene.__doc__}")
    print("  q  끝내기")


def run(key):
    for name, scene in SCENES:
        if name == key:
            print(f"\n== {key}. {scene.__doc__}")
            try:
                scene()
            except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as e:
                print(f"  ! 장면을 끝내지 못했습니다: {e}")
            return True
    return False


def main():
    # 터미널에서는 콘솔 글꼴에 맡기고, 파이프로 받을 때만 UTF-8로 내보낸다.
    if sys.stdout.isatty():
        sys.stdout.reconfigure(errors="replace")
    else:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print("차곡 시연 콘솔. 이 값은 Virtual Console로 에뮬레이터에 주입합니다. 실차 값이 아닙니다.")
    # 점검만 할 때는 아무것도 바꾸지 않는다. 포트와 시간대가 어떤 상태인지 그대로 본다.
    if sys.argv[1:] == ["p"]:
        run("p")
        return 0
    adb("forward", "tcp:8088", "tcp:8088", show=False)
    # 콜드 부트하면 시간대가 돌아간다. 상태 표시줄과 리포트 시각이 어긋나지 않게 켤 때마다 맞춘다.
    adb("shell", "cmd", "alarm", "set-timezone", TIME_ZONE, show=False)
    try:
        request("POST", "/browser/session", client=CLIENT)
    except (urllib.error.URLError, OSError) as e:
        print(f"Virtual Console에 연결하지 못했습니다({CONSOLE}): {e}\n에뮬레이터가 켜져 있는지 확인하세요.")
        return 1
    threading.Thread(target=keep_session, daemon=True).start()
    time.sleep(1)  # 세션이 잡힌 뒤에 쓴다.

    keys = sys.argv[1:]
    if keys:
        for key in keys:
            if not run(key):
                print(f"모르는 장면: {key}")
                return 2
            time.sleep(0.5)
        return 0

    print(PT_ORDER)
    menu()
    while True:
        try:
            key = input("\n장면 번호> ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return 0
        if key == "q":
            return 0
        if key and not run(key):
            menu()


if __name__ == "__main__":
    sys.exit(main())
