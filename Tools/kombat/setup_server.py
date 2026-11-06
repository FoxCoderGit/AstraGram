from deploy import connect,run
from pathlib import Path

with connect() as c:
    print(run(c,"install -d -m 755 /opt/ghostgram-kombat-build; command -v curl >/dev/null; if [ ! -x /opt/ghostgram-kombat-toolchain/cargo/bin/rustup ]; then curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o /opt/ghostgram-kombat-build/rustup.sh; RUSTUP_HOME=/opt/ghostgram-kombat-toolchain/rustup CARGO_HOME=/opt/ghostgram-kombat-toolchain/cargo sh /opt/ghostgram-kombat-build/rustup.sh -y --profile minimal --no-modify-path; fi; /opt/ghostgram-kombat-toolchain/cargo/bin/rustup --version"))
    print('Isolated Rust toolchain ready.')
