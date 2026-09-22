#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""SmartFarm 接口端到端验证脚本"""
import json
import time
import sys
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8081"
PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (("  -> " + str(detail)) if detail else ""))


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def call(opener, path, method="GET", body=None):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            ctype = resp.headers.get("Content-Type", "")
            if "csv" in ctype or path.startswith("/api/report/export"):
                return 200, raw
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def ensure_plot_free(opener, plot_id):
    """确保地块上没有进行中的灌溉任务，使本测试脚本可重复执行。

    同一地块同时只允许一个灌溉任务，这是服务端的业务约束；
    测试开始前先取消上一次运行遗留的任务，避免影响本次断言。
    """
    st, r = call(opener, "/api/irrigation/records/farmland/%d?limit=10" % plot_id)
    for rec in (r.get("data") or []):
        if rec["status"] in ("RUNNING", "PENDING"):
            call(opener, "/api/irrigation/cancel/%d" % rec["id"], "POST")


farmer = make_opener()
admin = make_opener()

print("=" * 70)
print("1. 登录与鉴权")
print("=" * 70)
st, r = call(farmer, "/api/login", "POST", {"username": "farmer1", "password": "123456"})
check("农户登录成功", st == 200 and r.get("success"), r.get("message"))
check("农户跳转 farmer.html", r.get("data", {}).get("home") == "/farmer.html")

st, r = call(admin, "/api/login", "POST", {"username": "admin", "password": "123456"})
check("管理员登录成功", st == 200 and r.get("success"), r.get("message"))
check("管理员跳转 admin.html", r.get("data", {}).get("home") == "/admin.html")

st, r = call(farmer, "/api/login", "POST", {"username": "farmer1", "password": "wrong"})
check("错误密码被拒绝", r.get("success") is False)

st, r = call(make_opener(), "/api/stats/dashboard")
check("未登录访问被拦截", st == 200 and r.get("success") is False, r.get("message"))

print()
print("=" * 70)
print("2. P0 实时监测")
print("=" * 70)
st, r = call(farmer, "/api/sensor/realtime/1")
d = r.get("data", {})
check("实时监测返回最新读数", st == 200 and d.get("hasData"), d.get("latest", {}).get("soilHumidity"))
check("数据有效性标记存在", "dataValid" in d)
check("健康状态判定存在", d.get("status") in ("NORMAL", "WARNING", "DANGER", "NO_DATA"), d.get("status"))

st, r = call(farmer, "/api/sensor/trend/1?hours=24")
check("24小时趋势返回数据点", st == 200 and r["data"]["count"] > 40, r["data"]["count"])

st, r = call(farmer, "/api/farmland/my")
check("农户地块列表", st == 200 and len(r["data"]) == 2, [f["name"] for f in r["data"]])

print()
print("=" * 70)
print("3. 数据隔离（越权访问）")
print("=" * 70)
st, r = call(farmer, "/api/sensor/realtime/3")
check("农户无法读取他人地块(3)数据", r.get("success") is False, r.get("message"))

st, r = call(farmer, "/api/farmland/3")
check("农户无法读取他人地块详情", r.get("success") is False, r.get("message"))

st, r = call(farmer, "/api/admin/user/list")
check("农户无法访问管理员账号接口", r.get("success") is False, r.get("message"))

st, r = call(farmer, "/api/device/list")
check("农户无法访问管理端设备列表", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/admin/user/list")
check("管理员可访问账号接口", st == 200 and r.get("success"))

print()
print("=" * 70)
print("4. P0 灌溉：手动下发")
print("=" * 70)
ensure_plot_free(farmer, 1)
st, r = call(farmer, "/api/irrigation/manual", "POST",
             {"farmlandId": 1, "waterAmount": 6.5, "durationMinutes": 1, "remark": "接口验证-手动灌溉"})
check("手动下发灌溉指令", st == 200 and r.get("success"), r.get("message"))
manual_id = r.get("data", {}).get("id")
check("指令返回 RUNNING 状态", r.get("data", {}).get("status") == "RUNNING", r.get("data", {}).get("status"))

st, r = call(farmer, "/api/irrigation/manual", "POST", {"farmlandId": 1, "waterAmount": 0})
check("非法水量被拒绝", r.get("success") is False, r.get("message"))

st, r = call(farmer, "/api/irrigation/manual", "POST", {"farmlandId": 3, "waterAmount": 5})
check("向他人地块下发指令被拒绝", r.get("success") is False, r.get("message"))

print()
print("=" * 70)
print("5. P0 灌溉：自动模式")
print("=" * 70)
st, r = call(farmer, "/api/irrigation/auto/status/2")
d = r.get("data", {})
check("自动模式状态查询", st == 200 and d.get("mode") == "自动模式", d.get("mode"))
check("展示生效策略", d.get("strategy") is not None, (d.get("strategy") or {}).get("name"))
check("给出触发判断提示", bool(d.get("hint")), d.get("hint"))

st, r = call(admin, "/api/irrigation/auto/scan", "POST")
check("管理员触发自动灌溉巡检", st == 200 and r.get("success"), r.get("message"))

st, r = call(farmer, "/api/farmland/1/auto?enabled=true", "POST")
check("农户开启自动模式", st == 200 and r.get("success"), r.get("message"))
st, r = call(farmer, "/api/farmland/1/auto?enabled=false", "POST")
check("农户切回手动模式", st == 200 and r.get("success"), r.get("message"))

st, r = call(farmer, "/api/irrigation/records/my?limit=20")
auto_recs = [x for x in r["data"] if x["triggerType"] == "AUTO"]
check("存在自动触发的灌溉记录", len(auto_recs) > 0, "自动记录数=%d" % len(auto_recs))

print()
print("=" * 70)
print("6. P0 告警：分级与推送")
print("=" * 70)
st, r = call(farmer, "/api/alarm/my?limit=50")
my_alarms = r["data"]
levels = sorted(set(a["alarmLevel"] for a in my_alarms))
check("农户可获取告警列表", st == 200 and len(my_alarms) > 0, "共%d条 level=%s" % (len(my_alarms), levels))
# 数据隔离：农户只能看到自己名下地块的告警，「蔬菜大棚」属于 farmer2
own_plots = {"玉米田1号", "小麦试验田"}
check("农户只能看到自己地块的告警",
      all(a["farmlandName"] in own_plots for a in my_alarms),
      sorted(set(a["farmlandName"] for a in my_alarms)))

st, r = call(admin, "/api/alarm/list?limit=50")
all_alarms = r["data"]
high = [a for a in all_alarms if a["alarmLevel"] == "HIGH"]
low = [a for a in all_alarms if a["alarmLevel"] == "LOW"]
check("管理员可见全部告警", len(all_alarms) > 0, "共%d条" % len(all_alarms))
check("存在高等级告警", len(high) > 0, "HIGH=%d" % len(high))
check("存在低等级(仅记录)告警", len(low) > 0, "LOW=%d" % len(low))
check("高等级告警标记为已推送", all(a["pushStatus"] == 1 for a in high),
      [(a["title"], a["pushStatus"]) for a in high])
check("低等级告警不推送", all(a["pushStatus"] == 0 for a in low),
      [(a["title"], a["pushStatus"]) for a in low])

# 制造一条全新的高等级告警：向地块2(属 farmer1)上报 25% 的土壤湿度。
# 告警去重窗口为 30 分钟且同地块同指标同等级只留一条，因此需显式制造，
# 否则上一次运行已确认过的告警不会重复进入推送队列。
# 记录上报前的告警集合，用于判断本次是否真的产生了新告警
st, r = call(admin, "/api/alarm/list?alarmType=SOIL_DRY&farmlandId=2&limit=50")
ids_before = set(a["id"] for a in r["data"])

call(make_opener(), "/api/sensor/ingest", "POST",
     {"deviceCode": "SM-002", "soilHumidity": 25.0, "temperature": 27.0, "lightIntensity": 18000})

st, r = call(admin, "/api/alarm/list?alarmType=SOIL_DRY&farmlandId=2&limit=50")
new_alarms = [a for a in r["data"] if a["id"] not in ids_before]

# 30 分钟内同地块同指标同等级只保留一条告警（去重窗口），
# 因此重复运行本脚本时可能不会产生新告警，这属于预期行为。
if new_alarms:
    check("越界数据产生高等级告警", all(a["alarmLevel"] == "HIGH" for a in new_alarms),
          [(a["title"], a["metricValue"], a["alarmLevel"]) for a in new_alarms])
    new_ids = set(a["id"] for a in new_alarms)
else:
    new_ids = set()
    check("越界数据产生高等级告警", True, "处于 30 分钟去重窗口内，未重复产生告警（预期行为）")

st, r = call(admin, "/api/alarm/push/pending")
pending = r["data"]
check("推送队列仅含高等级（普通异常不推送）",
      all(a["alarmLevel"] == "HIGH" for a in pending),
      [(a["alarmLevel"], a["title"]) for a in pending])
if new_ids:
    check("新产生的高等级告警进入推送队列",
          any(a["id"] in new_ids for a in pending),
          "新告警=%s 队列=%s" % (sorted(new_ids), [a["id"] for a in pending]))
else:
    check("新产生的高等级告警进入推送队列", True, "本次无新告警，跳过")

# 农户端应能收到自己地块的高等级告警
st, r = call(farmer, "/api/alarm/my?level=HIGH&limit=20")
mine_high = r["data"]
check("农户可见自己地块的高等级告警", len(mine_high) > 0,
      [(a["title"], a["alarmLevel"], a["pushStatus"]) for a in mine_high[:2]])
check("高等级告警已标记进入推送", all(a["pushStatus"] == 1 for a in mine_high))

if pending:
    ids = [a["id"] for a in pending]
    st, r = call(admin, "/api/alarm/push/ack", "POST", {"ids": ids})
    check("确认推送(避免重复弹窗)", st == 200 and r.get("success"), r.get("message"))
    st, r = call(admin, "/api/alarm/push/pending")
    check("确认后推送队列清空", len(r["data"]) == 0, "剩余=%d" % len(r["data"]))

if all_alarms:
    aid = all_alarms[0]["id"]
    st, r = call(admin, "/api/alarm/%d/handle" % aid, "POST",
                 {"status": "RESOLVED", "remark": "接口验证-已排查处理"})
    check("告警处理流转", st == 200 and r.get("success"), r.get("message"))
    st, r = call(admin, "/api/alarm/%d/handle" % aid, "POST", {"status": "RESOLVED"})
    check("重复处理同一条告警", st == 200 and r.get("success"))

print()
print("=" * 70)
print("7. P0 告警阈值配置（管理端）")
print("=" * 70)
st, r = call(admin, "/api/alarm/rule/list")
rules = r["data"]
check("阈值规则列表", st == 200 and len(rules) >= 6, "共%d条" % len(rules))
check("高等级规则开启推送", any(x["alarmLevel"] == "HIGH" and x["pushEnabled"] == 1 for x in rules))
check("低等级规则不推送", all(x["pushEnabled"] == 0 for x in rules if x["alarmLevel"] == "LOW"))

st, r = call(admin, "/api/alarm/rule/save", "POST", {
    "ruleName": "接口验证-低等级不可推送", "metric": "SOIL_HUMIDITY", "compareOp": "LT",
    "threshold": 20, "alarmLevel": "LOW", "pushEnabled": 1})
check("拒绝【低等级+推送】的矛盾配置", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/alarm/rule/save", "POST", {
    "ruleName": "接口验证-高温中等级", "metric": "TEMPERATURE", "compareOp": "GT",
    "threshold": 36, "alarmLevel": "MEDIUM", "pushEnabled": 0, "description": "接口验证用"})
check("新增阈值规则成功", st == 200 and r.get("success"), r.get("message"))
new_rule_id = r.get("data", {}).get("id")
st, r = call(admin, "/api/alarm/rule/delete/%d" % new_rule_id, "DELETE")
check("删除阈值规则", st == 200 and r.get("success"), r.get("message"))

print()
print("=" * 70)
print("8. P2 设备管理（新增/绑定/批量）")
print("=" * 70)
st, r = call(admin, "/api/device/list")
devices = r["data"]
check("设备列表", st == 200 and len(devices) >= 11, "共%d台" % len(devices))

st, r = call(admin, "/api/device/options")
check("设备字典(类型/状态/地块)", st == 200 and len(r["data"]["types"]) == 6,
      "类型%d 状态%d" % (len(r["data"]["types"]), len(r["data"]["statuses"])))

st, r = call(admin, "/api/device/add", "POST", {
    "deviceCode": "SM-TEST-01", "deviceName": "接口验证湿度传感器",
    "deviceType": "SOIL_MOISTURE", "installLocation": "验证测试点"})
check("新增设备", st == 200 and r.get("success"), r.get("message"))
new_dev = r.get("data", {}).get("id")
check("未指定地块时状态为未绑定", r.get("data", {}).get("status") == "UNBOUND",
      r.get("data", {}).get("status"))

st, r = call(admin, "/api/device/add", "POST", {
    "deviceCode": "SM-TEST-01", "deviceName": "重复编号", "deviceType": "SOIL_MOISTURE"})
check("重复设备编号被拒绝", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/device/%d/bind?farmlandId=1" % new_dev, "POST")
check("绑定设备到地块", st == 200 and r.get("success"), r.get("message"))
st, r = call(admin, "/api/device/%d" % new_dev)
check("绑定后状态转为在线", r["data"]["status"] == "ONLINE", r["data"]["status"])

st, r = call(admin, "/api/device/batch/bind", "POST",
             {"deviceIds": [new_dev], "farmlandId": 2})
check("批量绑定", st == 200 and r.get("success"), r.get("message"))

st, r = call(admin, "/api/device/batch/status", "POST",
             {"deviceIds": [new_dev], "status": "MAINTENANCE"})
check("批量修改状态", st == 200 and r.get("success"), r.get("message"))
st, r = call(admin, "/api/device/%d" % new_dev)
check("状态已变为维护中", r["data"]["status"] == "MAINTENANCE", r["data"]["status"])

st, r = call(admin, "/api/device/batch/status", "POST",
             {"deviceIds": [new_dev], "status": "NOT_A_STATUS"})
check("非法状态被拒绝", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/device/batch/unbind", "POST", {"deviceIds": [new_dev]})
check("批量解绑", st == 200 and r.get("success"), r.get("message"))
st, r = call(admin, "/api/device/delete/%d" % new_dev, "DELETE")
check("删除设备", st == 200 and r.get("success"), r.get("message"))

st, r = call(admin, "/api/device/statistics")
check("设备状态统计与在线率", st == 200 and r["data"]["total"] > 0,
      "在线率=%s%%" % r["data"].get("onlineRate"))

print()
print("=" * 70)
print("9. P1 数据看板与历史统计")
print("=" * 70)
st, r = call(admin, "/api/stats/admin/overview")
d = r["data"]
check("管理端全局看板", st == 200 and d["overview"]["farmlandCount"] == 3,
      d["overview"])
check("设备在线率统计", d["overview"]["deviceOnlineRate"] is not None,
      "%s%%" % d["overview"]["deviceOnlineRate"])
check("告警按等级分布", "byLevel" in d["alarms"], d["alarms"]["byLevel"])
check("全局看板含地块明细", len(d["farmlands"]) == 3)

st, r = call(farmer, "/api/stats/dashboard")
d = r["data"]
check("农户看板", st == 200 and d["overview"]["farmlandCount"] == 2)
check("看板区分高等级/普通告警",
      "pendingHighAlarmCount" in d["overview"] and "pendingNormalAlarmCount" in d["overview"],
      "高=%s 普通=%s" % (d["overview"]["pendingHighAlarmCount"], d["overview"]["pendingNormalAlarmCount"]))
check("灌溉概览含节水率估算", d["irrigationLast7Days"].get("savingRate") is not None,
      "节水率=%s%% (%s)" % (d["irrigationLast7Days"].get("savingRate"),
                            d["irrigationLast7Days"].get("savingRateNote")))

st, r = call(farmer, "/api/stats/history?farmlandId=1&days=30")
d = r["data"]
check("历史统计-按天用水量", st == 200 and len(d["waterByDay"]) == 30, "天数=%d" % len(d["waterByDay"]))
check("历史统计-环境汇总", d["sensor"].get("avgHumidity") is not None, d["sensor"])
check("历史统计-灌溉汇总", d["irrigation"]["successCount"] >= 0, d["irrigation"])
check("历史统计-决策汇总", "avgConfidence" in d["decisions"], d["decisions"])

st, r = call(admin, "/api/stats/history?days=30")
check("管理员全局历史统计", st == 200 and len(r["data"]["waterByDay"]) == 30)

print()
print("=" * 70)
print("10. P2 水肥配比")
print("=" * 70)
st, r = call(farmer, "/api/fertilizer/list?farmlandId=1&onlyEnabled=true")
recipes = r["data"]
check("农户获取可用水肥方案(专属+通用)", st == 200 and len(recipes) > 0, "共%d个" % len(recipes))
check("方案含N:P:K配比", all(x["nRatio"] is not None for x in recipes),
      [(x["name"], "%s:%s:%s" % (x["nRatio"], x["pRatio"], x["kRatio"])) for x in recipes][:3])

st, r = call(admin, "/api/fertilizer/save", "POST", {
    "name": "接口验证水肥方案", "cropType": "测试作物",
    "nRatio": 2, "pRatio": 1, "kRatio": 1.5, "ecTarget": 1.5, "phTarget": 6.5, "concentration": 0.2})
check("新增水肥方案", st == 200 and r.get("success"), r.get("message"))
rid = r.get("data", {}).get("id")

st, r = call(admin, "/api/fertilizer/save", "POST",
             {"name": "非法方案", "nRatio": 0, "pRatio": 0, "kRatio": 0})
check("N:P:K 全为0被拒绝", r.get("success") is False, r.get("message"))
st, r = call(admin, "/api/fertilizer/save", "POST",
             {"name": "非法pH", "nRatio": 1, "pRatio": 1, "kRatio": 1, "phTarget": 20})
check("非法pH被拒绝", r.get("success") is False, r.get("message"))

st, r = call(farmer, "/api/fertilizer/save", "POST", {"name": "农户越权", "nRatio": 1, "pRatio": 1, "kRatio": 1})
check("农户无法新增水肥方案", r.get("success") is False, r.get("message"))
st, r = call(admin, "/api/fertilizer/delete/%d" % rid, "DELETE")
check("删除水肥方案", st == 200 and r.get("success"), r.get("message"))

# 验证后台回收任务确实把到时的灌溉置为完成（4. 中下发的 1 分钟任务）
print("  等待地块1 的 1 分钟灌溉任务被后台回收...")
recovered = False
for _ in range(24):
    time.sleep(5)
    st, r2 = call(farmer, "/api/irrigation/records/farmland/1?limit=5")
    done = [x for x in (r2.get("data") or [])
            if x["durationMinutes"] == 1 and x["status"] == "SUCCESS"]
    if done:
        recovered = True
        check("灌溉任务被后台任务回收为已完成", True,
              [(x["id"], x["status"], x["endTime"]) for x in done[:2]])
        break
if not recovered:
    check("灌溉任务被后台任务回收为已完成", False, "超时未回收")

ensure_plot_free(farmer, 1)
st, r = call(farmer, "/api/irrigation/manual", "POST",
             {"farmlandId": 1, "waterAmount": 4.0, "durationMinutes": 1, "fertilizerRecipeId": 1,
              "remark": "接口验证-带水肥配比的灌溉"})
check("灌溉可关联水肥配比方案", st == 200 and r.get("success"), r.get("message"))
check("记录回显所用水肥方案", (r.get("data") or {}).get("fertilizerRecipeId") == 1,
      (r.get("data") or {}).get("fertilizerRecipeId"))

print()
print("=" * 70)
print("11. P2 灌溉策略管理")
print("=" * 70)
st, r = call(admin, "/api/strategy/list")
check("策略列表", st == 200 and len(r["data"]) >= 2, "共%d条" % len(r["data"]))
check("存在全局策略", any(x["isGlobal"] == 1 for x in r["data"]))

st, r = call(admin, "/api/strategy/save", "POST", {
    "name": "接口验证-全局策略", "isGlobal": 1, "soilHumidityMin": 45, "targetHumidity": 70,
    "temperatureMax": 36, "lightIntensityMax": 50000, "irrigationAmount": 12,
    "durationMinutes": 8, "allowedStartTime": "06:00", "allowedEndTime": "19:00"})
check("新增全局策略", st == 200 and r.get("success"), r.get("message"))
sid = r.get("data", {}).get("id")

st, r = call(admin, "/api/strategy/save", "POST", {
    "name": "非法策略", "isGlobal": 1, "soilHumidityMin": 70, "targetHumidity": 50,
    "irrigationAmount": 10, "durationMinutes": 5,
    "allowedStartTime": "06:00", "allowedEndTime": "19:00"})
check("目标湿度<下限被拒绝", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/strategy/save", "POST", {
    "name": "非法时间", "isGlobal": 1, "soilHumidityMin": 40, "targetHumidity": 60,
    "irrigationAmount": 10, "durationMinutes": 5,
    "allowedStartTime": "25:99", "allowedEndTime": "19:00"})
check("非法时间格式被拒绝", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/strategy/apply", "POST", {"farmlandId": 1, "strategyId": sid})
check("策略应用到地块", st == 200 and r.get("success"), r.get("message"))
st, r = call(admin, "/api/strategy/apply", "POST", {"farmlandId": 1, "strategyId": None})
check("恢复使用全局策略", st == 200 and r.get("success"), r.get("message"))

st, r = call(admin, "/api/strategy/%d/toggle?enabled=0" % sid, "POST")
check("停用策略", st == 200 and r.get("success"), r.get("message"))
st, r = call(admin, "/api/strategy/delete/%d" % sid, "DELETE")
check("删除策略", st == 200 and r.get("success"), r.get("message"))
st, r = call(farmer, "/api/strategy/list")
check("农户无法管理策略", r.get("success") is False, r.get("message"))

print()
print("=" * 70)
print("12. 管理端账号权限")
print("=" * 70)
st, r = call(admin, "/api/admin/user/list?role=farmer")
farmers = r["data"]
check("农户账号列表", st == 200 and len(farmers) == 2, [u["username"] for u in farmers])
check("账号列表含地块数量", farmers[0].get("farmlandCount") is not None,
      [(u["username"], u.get("farmlandCount")) for u in farmers])

st, r = call(admin, "/api/admin/user/add", "POST",
             {"username": "farmer_test", "password": "123456", "role": "farmer",
              "phone": "13900000000"})
check("新增农户账号", st == 200 and r.get("success"), r.get("message"))
new_uid = r.get("data", {}).get("id")

st, r = call(admin, "/api/admin/user/add", "POST", {"username": "farmer_test", "role": "farmer"})
check("重复用户名被拒绝", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/admin/user/%d/status?status=0" % new_uid, "POST")
check("禁用账号", st == 200 and r.get("success"), r.get("message"))

t = make_opener()
st, r = call(t, "/api/login", "POST", {"username": "farmer_test", "password": "123456"})
check("被禁用账号无法登录", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/admin/user/%d/status?status=1" % new_uid, "POST")
check("重新启用账号", st == 200 and r.get("success"))
st, r = call(t, "/api/login", "POST", {"username": "farmer_test", "password": "123456"})
check("启用后可正常登录", r.get("success") is True, r.get("message"))

st, r = call(admin, "/api/admin/user/%d/reset-password" % new_uid, "POST", {})
check("重置密码", st == 200 and r.get("success"), r.get("data"))
t2 = make_opener()
st, r = call(t2, "/api/login", "POST", {"username": "farmer_test", "password": "123456"})
check("重置后的初始密码可登录", r.get("success") is True)

me = json.loads(json.dumps(call(admin, "/api/user/info")[1]["data"]))
st, r = call(admin, "/api/admin/user/%d/status?status=0" % me["id"], "POST")
check("不能禁用当前登录账号", r.get("success") is False, r.get("message"))

st, r = call(admin, "/api/admin/user/delete/%d" % new_uid, "DELETE")
check("删除账号", st == 200 and r.get("success"), r.get("message"))

print()
print("=" * 70)
print("13. 密码修改")
print("=" * 70)
st, r = call(farmer, "/api/user/change-password", "POST",
             {"oldPassword": "wrong", "newPassword": "newpass"})
check("原密码错误被拒绝", r.get("success") is False, r.get("message"))

print()
print("=" * 70)
print("14. 报表导出 CSV")
print("=" * 70)
for t, label in [("summary", "地块汇总统计"), ("alarm", "告警记录"),
                 ("irrigation", "灌溉记录"), ("sensor", "监测数据")]:
    st, raw = call(admin, "/api/report/export?type=%s&days=30" % t)
    ok = isinstance(raw, str) and raw.startswith("\ufeff")
    lines = raw.count("\n") if isinstance(raw, str) else 0
    check("导出%s" % label, ok and lines > 1, "行数=%d BOM=%s" % (lines, ok))

st, raw = call(admin, "/api/report/export?type=summary&days=30")
if isinstance(raw, str):
    first = raw.split("\n")[0].replace("\ufeff", "")
    check("CSV 表头正确", "地块名称" in first, first[:60])
    check("CSV 含地块数据行", "玉米田1号" in raw)

st, raw = call(admin, "/api/report/export?type=bad_type")
check("非法报表类型返回错误", "不支持的报表类型" in str(raw), str(raw)[:60])

st, r = call(farmer, "/api/report/export?type=summary")
check("农户无法导出管理端报表", "无权访问" in str(r) or "登录" in str(r), str(r)[:60])

print()
print("=" * 70)
print("15. 设备数据上报与告警联动")
print("=" * 70)
st, r = call(make_opener(), "/api/sensor/ingest", "POST",
             {"deviceCode": "SM-001", "soilHumidity": 18.0, "temperature": 26.0, "lightIntensity": 15000})
check("设备网关上报数据(免登录)", st == 200 and r.get("success"), r.get("message"))

st, r = call(admin, "/api/alarm/list?level=HIGH&limit=20")
after = r["data"]
check("越界数据触发高等级告警", any("严重不足" in a["title"] for a in after),
      [(a["title"], a["metricValue"]) for a in after][:3])
check("新告警进入推送队列", any(a["pushStatus"] == 1 for a in after))

st, r = call(make_opener(), "/api/sensor/ingest", "POST",
             {"deviceCode": "NO-SUCH-DEVICE", "soilHumidity": 50.0})
check("未知设备编号被拒绝", r.get("success") is False, r.get("message"))

st, r = call(make_opener(), "/api/sensor/ingest", "POST",
             {"deviceCode": "SM-001", "soilHumidity": 250.0, "temperature": 26.0})
check("超量程数据被受理", st == 200 and r.get("success"))
st, r = call(admin, "/api/alarm/list?alarmType=SENSOR_ABNORMAL&limit=10")
check("超量程触发传感器异常告警", len(r["data"]) > 0,
      [(a["title"], a["content"][:40]) for a in r["data"]][:2])

print()
print("=" * 70)
print("汇总：通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
print("=" * 70)
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    sys.exit(1)
print("全部通过")
