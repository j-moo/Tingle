"""Compatibility entrypoint; all environments use app.main's composition."""

from app.config import Settings


def create_app():
    from app.main import create_app as build_app

    return build_app(Settings())
