"""Run explicit deployment commands over the already pinned owner SSH identity."""
import sys
from deploy import connect,run
with connect() as c:
    print(run(c,sys.argv[1]))
