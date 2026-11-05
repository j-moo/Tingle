"""Read this lab firmware's USB console without flashing or requesting a reset."""

import argparse
import time
from pathlib import Path

import serial
from serial.tools import list_ports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=45)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.seconds
    detected = False
    with args.output.open("wb") as log:
        while time.monotonic() < deadline:
            # This VID/PID is the Zephyr lab application, not the UF2 bootloader.
            ports = [p for p in list_ports.comports() if (p.vid, p.pid) == (0x2FE3, 0x0100)]
            if len(ports) > 1:
                raise SystemExit("Multiple lab boards found; connect only the board being diagnosed.")
            if not ports:
                time.sleep(0.25)
                continue
            port = serial.Serial(port=None, baudrate=115200, timeout=0.25)
            port.port = ports[0].device
            port.dtr = True
            port.rts = False
            try:
                port.open()
                detected = True
                print(f"Reading {port.port} at 115200; no reset request", flush=True)
                while time.monotonic() < deadline:
                    data = port.read(4096)
                    if data:
                        log.write(data)
                        log.flush()
                        print(data.decode("utf-8", errors="replace"), end="", flush=True)
            except serial.SerialException as error:
                print(f"Console unavailable: {error}", flush=True)
                time.sleep(0.25)
            finally:
                port.close()
    if not detected:
        raise SystemExit("No lab firmware USB console appeared during the observation window.")


if __name__ == "__main__":
    main()
