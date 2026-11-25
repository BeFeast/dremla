"""Controlled errors and durable local JSON writes."""
import hashlib
import json
import os
from pathlib import Path
import tempfile


class ServiceError(ValueError):
    def __init__(self, code, message, status=400):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def require(condition, code, message, status=400):
    if not condition:
        raise ServiceError(code, message, status)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode()


def parse_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "invalid-json", "Duplicate JSON field.")
            result[key] = value
        return result
    def constant(_):
        raise ServiceError("invalid-json", "Nonfinite JSON number.")
    return json.loads(raw, object_pairs_hook=unique, parse_constant=constant)


def require_utf8(value):
    """Validate all request strings before any mutation, including JSON keys."""
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError:
            raise ServiceError("invalid-request", "Request strings must be valid UTF-8.") from None
    elif isinstance(value, dict):
        for key, item in value.items():
            require_utf8(key)
            require_utf8(item)
    elif isinstance(value, list):
        for item in value:
            require_utf8(item)


def read_json(path):
    return parse_json(Path(path).read_bytes())


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
            os.replace(temporary, path)
            sync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)


def digest(path):
    with Path(path).open("rb") as source:
        if hasattr(hashlib, "file_digest"):
            return hashlib.file_digest(source, "sha256").hexdigest()
        result = hashlib.sha256()
        for block in iter(lambda: source.read(65536), b""):
            result.update(block)
        return result.hexdigest()


class Cancelled(Exception):
    pass


def checkpoint(cancelled, delay=0):
    if cancelled.wait(delay):
        raise Cancelled()
