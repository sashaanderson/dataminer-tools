#!/usr/bin/env python

import csv
import fileinput
import sys

from signal import signal, SIGPIPE, SIG_DFL
signal(SIGPIPE, SIG_DFL)

reader = csv.reader(fileinput.input(mode='r'), delimiter=',')
writer = csv.writer(sys.stdout, 'excel-tab')

try:
    for row in reader:
        writer.writerow(row)
except csv.Error as e:
    sys.exit('file %s, line %d: %s' % (
        fileinput.filename(),
        fileinput.filelineno(),
        e))
