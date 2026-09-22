"""Moto 5.2.2 published Lambda copies omit DockerModel's lazy-client field.

The class default restores lazy initialization without changing invocation results.
This compatibility workaround is confined to the emulator, never the toolkit.
"""
from moto.utilities.docker_utilities import DockerModel
from moto.server import main

DockerModel._DockerModel__docker_client = None
main(['-H', '0.0.0.0', '-p', '5000'])
