# Native dependency

The `llama.cpp` source is tracked as a Git submodule at `third_party/llama.cpp`.
Initialize it with:

```bash
git submodule update --init --recursive
```

The pinned revision is recorded in the root `.gitmodules` and Git tree entry.
