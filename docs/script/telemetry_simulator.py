#!/usr/bin/env python3
"""故障诊断遥测模拟器。

模拟数据阶段没有真实遥测上报，"现在有没有故障"这类实时问题查不到当前窗口数据，
只能回退到历史数据。本脚本按标称采样周期向 dcma 库的受控表持续写入新的模拟遥测，
使实时窗口始终有数据可查；并按预设剧本间歇性注入报警/故障段，便于演示与验收。

数据契约（与 real_data_01~03 现有数据一致）：
- F 类故障码写入 fault_code，A 类报警码写入 alarm_code，无代码写 0；
- status：0 = 正常，42 = 异常段；device_name 与 inverter_name 相同；
- timestamp 与 date + time、create_time 使用同一业务时间（Asia/Shanghai）。

用法：
  python3 telemetry_simulator.py --seed      # 一次性回填 real_data_04 的历史正常数据
  python3 telemetry_simulator.py             # 常驻运行：每 4 秒为每台设备写入一条新遥测

环境变量（均有默认值，指向 dcma 遥测库）：
  TELEMETRY_SIM_HOST / TELEMETRY_SIM_PORT / TELEMETRY_SIM_USER /
  TELEMETRY_SIM_PASSWORD / TELEMETRY_SIM_DB / TELEMETRY_SIM_INTERVAL
"""

import argparse
import os
import random
import time
from datetime import datetime

import pymysql

DB_CONFIG = {
    "host": os.environ.get("TELEMETRY_SIM_HOST", "10.108.12.164"),
    "port": int(os.environ.get("TELEMETRY_SIM_PORT", "3306")),
    "user": os.environ.get("TELEMETRY_SIM_USER", "root"),
    "password": os.environ.get("TELEMETRY_SIM_PASSWORD", "707707"),
    "database": os.environ.get("TELEMETRY_SIM_DB", "dcma"),
}
INTERVAL_SECONDS = int(os.environ.get("TELEMETRY_SIM_INTERVAL", "4"))

# 每台设备一个剧本：
# - normal：始终正常；
# - alarm/fault：每 cycle 秒中的前 duration 秒注入对应代码段（status=42），
#   offset 用于错开各设备的注入时间。调度基于 epoch 无状态计算，重启不丢剧本。
DEVICES = [
    {"name": "G120电机1", "table": "real_data_01", "kind": "alarm", "code": "A07089",
     "cycle": 1800, "duration": 120, "offset": 0},
    {"name": "G120电机2", "table": "real_data_02", "kind": "fault", "code": "F30899",
     "cycle": 2400, "duration": 120, "offset": 600},
    {"name": "G120电机3", "table": "real_data_03", "kind": "fault", "code": "F07016",
     "cycle": 3000, "duration": 120, "offset": 1200},
    {"name": "G120电机4", "table": "real_data_04", "kind": "normal", "code": None,
     "cycle": 0, "duration": 0, "offset": 0},
]

# 各数值字段的 (基准值, 抖动幅度)，参考现有模拟数据的正常段。
NUMERIC_FIELDS = {
    "dc_voltage": (557.0, 3.0),
    "speed_setpoint": (800.0, 45.0),
    "speed_actual": (800.0, 60.0),
    "current_actual": (0.6, 0.25),
    "torque_setpoint": (0.2, 0.15),
    "torque_actual": (0.0, 0.1),
    "air_intake_temp": (26.0, 1.5),
    "motor_temp": (38.0, 2.0),
    "inverter_temp": (29.0, 1.5),
    "actual_power": (0.05, 0.08),
    "field_current": (0.56, 0.05),
    "torque_current": (0.15, 0.08),
    "inverter_radiator_temp": (30.5, 1.5),
    "inverter_load_rate": (39.0, 8.0),
    "motor_load_rate": (55.0, 10.0),
    "pulse_frequency": (3.5, 0.4),
    "motor_power": (0.45, 0.1),
    "feedback_power": (-0.01, 0.05),
}

INSERT_COLUMNS = (
    "timestamp, device_name, inverter_name, date, time, status, fault_code, alarm_code, "
    "control_word, status_word, dc_voltage, speed_setpoint, speed_actual, current_actual, "
    "torque_setpoint, torque_actual, air_intake_temp, motor_temp, inverter_temp, actual_power, "
    "field_current, torque_current, system_run_time, inverter_radiator_temp, inverter_load_rate, "
    "motor_load_rate, pulse_frequency, motor_power, feedback_power, create_time"
)


def active_code(device, epoch_seconds):
    """按剧本返回当前应注入的代码；正常段返回 (status, fault_code, alarm_code) = (0, 0, 0)。"""
    if device["kind"] == "normal" or not device["cycle"]:
        return "0", "0", "0"
    phase = (epoch_seconds + device["offset"]) % device["cycle"]
    if phase < device["duration"]:
        if device["kind"] == "fault":
            return "42", device["code"], "0"
        return "42", "0", device["code"]
    return "0", "0", "0"


def build_row(device, moment):
    """构造一条与现有模拟数据同构的记录。moment 为业务时间（本地时区）。

    先按列名组装字典，再严格依 INSERT_COLUMNS 顺序输出，避免 system_run_time
    这类夹在数值字段中间的非数值列造成整体错位。
    """
    status, fault_code, alarm_code = active_code(device, int(moment.timestamp()))
    text_time = moment.strftime("%Y-%m-%d %H:%M:%S")
    row = {
        "timestamp": text_time,
        "device_name": device["name"],
        "inverter_name": device["name"],
        "date": moment.strftime("%Y-%m-%d"),
        "time": moment.strftime("%H:%M:%S"),
        "status": status,
        "fault_code": fault_code,
        "alarm_code": alarm_code,
        "control_word": random.choice((5246, 5247)),
        "status_word": random.choice((10679, 8784)),
        "system_run_time": "0000:00:00",  # 保持现有数据的占位格式
        "create_time": text_time,         # 与业务时间一致，保证粗筛与精确窗口对齐
    }
    for column, (base, jitter) in NUMERIC_FIELDS.items():
        row[column] = round(random.uniform(base - jitter, base + jitter), 3)
    return [row[column] for column in (c.strip() for c in INSERT_COLUMNS.split(","))]


def connect():
    return pymysql.connect(autocommit=True, connect_timeout=10, **DB_CONFIG)


def insert_rows(connection, moment):
    placeholders = ", ".join(["%s"] * (len(NUMERIC_FIELDS) + 12))
    with connection.cursor() as cursor:
        for device in DEVICES:
            cursor.execute(
                f"INSERT INTO {device['table']} ({INSERT_COLUMNS}) VALUES ({placeholders})",
                build_row(device, moment))


def run_live():
    print(f"telemetry simulator started: devices={[d['name'] for d in DEVICES]}, "
          f"interval={INTERVAL_SECONDS}s", flush=True)
    connection = None
    while True:
        now = time.time()
        time.sleep(max(0.1, INTERVAL_SECONDS - (now % INTERVAL_SECONDS)))  # 对齐采样节拍
        moment = datetime.now()
        try:
            if connection is None or not connection.open:
                connection = connect()
            insert_rows(connection, moment)
        except Exception as error:  # noqa: BLE001 模拟器不能因单次失败退出
            print(f"{moment:%Y-%m-%d %H:%M:%S} insert failed: {error}", flush=True)
            try:
                if connection and connection.open:
                    connection.close()
            finally:
                connection = None
            time.sleep(5)


def run_seed(seed_start, row_count):
    """回填 real_data_04 的历史正常数据，风格与 real_data_01~03 保持一致。"""
    device = next(item for item in DEVICES if item["table"] == "real_data_04")
    connection = connect()
    placeholders = ", ".join(["%s"] * (len(NUMERIC_FIELDS) + 12))
    moment = seed_start
    with connection.cursor() as cursor:
        for _ in range(row_count):
            cursor.execute(
                f"INSERT INTO {device['table']} ({INSERT_COLUMNS}) VALUES ({placeholders})",
                build_row(device, moment))
            moment = datetime.fromtimestamp(moment.timestamp() + INTERVAL_SECONDS)
    connection.close()
    print(f"seeded {row_count} normal rows into {device['table']} "
          f"({seed_start:%Y-%m-%d %H:%M:%S} ~ )", flush=True)


def main():
    parser = argparse.ArgumentParser(description="故障诊断遥测模拟器")
    parser.add_argument("--seed", action="store_true", help="一次性回填 real_data_04 历史正常数据")
    parser.add_argument("--seed-start", default="2026-07-19 16:00:00", help="回填起始时间")
    parser.add_argument("--seed-rows", type=int, default=300, help="回填行数（默认300，约20分钟）")
    args = parser.parse_args()
    if args.seed:
        run_seed(datetime.strptime(args.seed_start, "%Y-%m-%d %H:%M:%S"), args.seed_rows)
    else:
        run_live()


if __name__ == "__main__":
    main()
