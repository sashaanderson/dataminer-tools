#!/usr/bin/env python

import logging
import os
import sqlite3
import sys
import time
import traceback
import win32api
import win32com.client
import win32gui
import win32process

from datetime import datetime


def main():
    logging.basicConfig(format='%(asctime)s - %(levelname)s - %(message)s',
                        level=logging.DEBUG)
    wmi = win32com.client.GetObject('winmgmts:')
    con = connect()
    logging.info("timetracker.py running")
    try:
        while True:
            poll(con, wmi)
            time.sleep(3)
    except:
        dt = datetime.now().strftime("%Y%m%d-%H%M%S")
        fh = open("timetracker-" + dt + ".err", "w")
        traceback.print_exc(file=fh)
        fh.close()
        raise


def connect():
    pathname = os.path.join(os.path.dirname(__file__), 'timetracker.db')
    con = sqlite3.connect(pathname)
    cur = con.cursor()
    cur.execute('''
      CREATE TABLE IF NOT EXISTS timetracker_process_header1 (
        hid1 INTEGER PRIMARY KEY,
        hostname TEXT NOT NULL,
        username TEXT NOT NULL,
        process_name TEXT NOT NULL,
        command_line TEXT NOT NULL
      )
    ''')
    cur.execute('''
      CREATE TABLE IF NOT EXISTS timetracker_process_header2 (
        hid2 INTEGER PRIMARY KEY,
        window_title TEXT NOT NULL UNIQUE
      )
    ''')
    cur.execute('''
      CREATE TABLE IF NOT EXISTS timetracker_process_detail (
        ts INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
        hid1 INTEGER NOT NULL,
        hid2 INTEGER NOT NULL,
        idle INTEGER NOT NULL,
        FOREIGN KEY (hid1) REFERENCES timetracker_process_header1(hid1)
        FOREIGN KEY (hid2) REFERENCES timetracker_process_header2(hid2)
      )
    ''')
    cur.execute('''
      CREATE VIEW IF NOT EXISTS timetracker_process_detail_view
      AS
      SELECT 
        datetime(d.ts,'unixepoch','localtime') as ts,
        d.idle,
        h1.hostname,
        h1.username,
        h1.process_name,
        h1.command_line,
        h2.window_title
      FROM timetracker_process_detail d
      JOIN timetracker_process_header1 h1 on h1.hid1 = d.hid1
      JOIN timetracker_process_header2 h2 on h2.hid2 = d.hid2
      ORDER BY d.ts
    ''')
    con.commit()
    return con


def poll(con, wmi):
    try:
        idle = win32api.GetTickCount() - win32api.GetLastInputInfo()
        idle = round(idle / 1000.0) # convert to seconds
    except Exception as e:
        logging.exception("Failed to get idle timer: " + repr(e))
        return
    try:
        hwnd = win32gui.GetForegroundWindow()
    except Exception as e:
        logging.exception("Failed to get foreground window: " + repr(e))
        return
    try:
        window_title = win32gui.GetWindowText(hwnd)
    except Exception as e:
        window_title = " "
    process_name = None
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        q = "Select * from Win32_Process Where ProcessId=" + str(pid)
        for p in wmi.ExecQuery(q):
            process_name = p.Name
            command_line = p.CommandLine
            print()
            print(p.Name, p.Properties_('ProcessId'))
            print("  CommandLine: " + p.CommandLine)
            print("  Idle: " + str(idle))
            print("  Title: " + window_title)
    except Exception as e:
        msg = "Failed to get process: " + window_title + ": " + repr(e)
        logging.exception(msg)
        return
    if process_name is None:
        return
    if command_line is None:
        command_line = " "
    try:
        cur = con.cursor()
        hid1 = get_hid1(cur, process_name, command_line)
        hid2 = get_hid2(cur, window_title)
        cur.execute('''
          insert into timetracker_process_detail (hid1, hid2, idle)
          values (?, ?, ?)
        ''', (hid1, hid2, idle))
        con.commit()
        cur.close()
    except Exception as e:
        logging.exception("Failed to save to database: " + repr(e))


def get_hid1(cur, process_name, command_line):
    hostname = os.environ['COMPUTERNAME']
    username = os.environ['USERNAME']
    if hostname is None: hostname = " "
    if username is None: username = " "
    for i in range(2):
        cur.execute('''
          select hid1
          from timetracker_process_header1
          where hostname = ?
          and username = ?
          and process_name = ?
          and command_line = ?
        ''', (hostname, username, process_name, command_line))
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute('''
          insert into timetracker_process_header1
          (hostname, username, process_name, command_line)
          values (?, ?, ?, ?)
        ''', (hostname, username, process_name, command_line))
    raise AssertionError()


def get_hid2(cur, window_title):
    for i in range(2):
        cur.execute('''
          select hid2
          from timetracker_process_header2
          where window_title = ?
        ''', (window_title,))
        row = cur.fetchone()
        if row:
            return row[0]
        cur.execute('''
          insert into timetracker_process_header2
          (window_title)
          values (?)
        ''', (window_title,))
    raise AssertionError()


if __name__ == '__main__':
    main()
