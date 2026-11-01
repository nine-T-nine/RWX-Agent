from _shared import timing
ID, TIER, SOURCES = "ab_timing", "T2", ["gateway"]
def compute(run):
    return timing(run)
