#!/usr/bin/env python3
"""Isolated entrypoint; only this repository's package is added to sys.path."""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dremla_collection.common import ServiceError
from dremla_collection.server import main

if __name__ == "__main__":
    try:
        main()
    except ServiceError as error:
        print("collection-service: " + error.message, file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError):
        print("collection-service: could not start the local service", file=sys.stderr)
        sys.exit(1)
