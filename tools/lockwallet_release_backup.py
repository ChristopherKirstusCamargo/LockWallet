#!/usr/bin/env python3
"""Encrypt/decrypt Lock Wallet's offline Android signing-key backup.

The encrypted bundle uses RSA-OAEP-SHA256 to wrap a fresh AES-256-GCM key.
The RSA private key must be kept offline and must never be committed.
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"LWKB\x01"
NONCE_SIZE = 12


def atomic_write(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def encrypt(public_certificate: Path, source: Path, destination: Path) -> None:
    certificate = x509.load_pem_x509_certificate(public_certificate.read_bytes())
    public_key = certificate.public_key()
    if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size < 3072:
        raise ValueError("The backup certificate must contain an RSA key of at least 3072 bits.")

    plaintext = source.read_bytes()
    content_key = AESGCM.generate_key(bit_length=256)
    nonce = os.urandom(NONCE_SIZE)
    wrapped_key = public_key.encrypt(
        content_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    if len(wrapped_key) > 65535:
        raise ValueError("Wrapped key is unexpectedly large.")

    header = MAGIC + struct.pack(">H", len(wrapped_key)) + wrapped_key + nonce
    ciphertext = AESGCM(content_key).encrypt(nonce, plaintext, header)
    atomic_write(destination, header + ciphertext)


def decrypt(private_key_path: Path, source: Path, destination: Path) -> None:
    payload = source.read_bytes()
    if len(payload) < len(MAGIC) + 2 + NONCE_SIZE + 16 or not payload.startswith(MAGIC):
        raise ValueError("Invalid or truncated Lock Wallet signing backup.")

    offset = len(MAGIC)
    wrapped_length = struct.unpack(">H", payload[offset : offset + 2])[0]
    offset += 2
    if wrapped_length < 256 or offset + wrapped_length + NONCE_SIZE + 16 > len(payload):
        raise ValueError("Invalid wrapped-key length in signing backup.")

    wrapped_key = payload[offset : offset + wrapped_length]
    offset += wrapped_length
    nonce = payload[offset : offset + NONCE_SIZE]
    offset += NONCE_SIZE
    header = payload[:offset]
    ciphertext = payload[offset:]

    private_key = serialization.load_pem_private_key(
        private_key_path.read_bytes(),
        password=None,
    )
    if not isinstance(private_key, rsa.RSAPrivateKey) or private_key.key_size < 3072:
        raise ValueError("The recovery key must be an unencrypted RSA private key of at least 3072 bits.")

    content_key = private_key.decrypt(
        wrapped_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    plaintext = AESGCM(content_key).decrypt(nonce, ciphertext, header)
    atomic_write(destination, plaintext)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    enc = subparsers.add_parser("encrypt", help="Encrypt a signing backup ZIP for a release.")
    enc.add_argument("--certificate", type=Path, required=True)
    enc.add_argument("--input", type=Path, required=True)
    enc.add_argument("--output", type=Path, required=True)

    dec = subparsers.add_parser("decrypt", help="Decrypt a signing backup ZIP using the offline recovery key.")
    dec.add_argument("--private-key", type=Path, required=True)
    dec.add_argument("--input", type=Path, required=True)
    dec.add_argument("--output", type=Path, required=True)

    args = parser.parse_args()
    try:
        if args.command == "encrypt":
            encrypt(args.certificate, args.input, args.output)
        else:
            decrypt(args.private_key, args.input, args.output)
    except (OSError, ValueError) as exc:
        print(f"Signing backup operation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
