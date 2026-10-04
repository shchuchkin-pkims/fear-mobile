#!/usr/bin/env python3
"""
GitHub Releases named and described from doc/release-notes/<tag>.md.

A notes file is written like an annotated tag message: the first line is
the title ("v0.6.0 – Key rotation, metadata privacy, group calls"), then a
blank line, then the description. The house style is in
doc/release-notes/README.md.

    release-notes.py publish TAG   create the release for TAG, or bring its
                                   title and description in line (tag push)
    release-notes.py sync          bring every existing release in line with
                                   its notes file (push to a branch)
    release-notes.py draft TAG     print a starting point for TAG's notes:
                                   the commits since the previous tag

Without a notes file, publish falls back to the annotated tag's message.
Needs git and the gh CLI (GH_TOKEN with contents: write) for publish/sync.
DRY_RUN=1 reports what would change and changes nothing.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

NOTES_DIR = "doc/release-notes"
TAG_RE = re.compile(r"^v\d+\.\d+\.\d+$")


def run(*args, check=False):
    return subprocess.run(args, capture_output=True, text=True, check=check)


def split(text):
    text = text.replace("\r\n", "\n").strip("\n")
    if not text:
        return None
    title, _, body = text.partition("\n")
    return title.strip(), body.strip("\n") + "\n"


def notes_for(tag):
    path = os.path.join(NOTES_DIR, tag + ".md")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return split(f.read())
    msg = run("git", "for-each-ref", f"refs/tags/{tag}", "--format=%(contents)").stdout
    return split(msg)


def current(tag):
    r = run("gh", "release", "view", tag, "--json", "name,body")
    return json.loads(r.stdout) if r.returncode == 0 else None


def same(rel, title, body):
    have = (rel.get("body") or "").replace("\r\n", "\n").strip()
    return rel.get("name") == title and have == body.strip()


def write_body(body):
    f = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8")
    f.write(body)
    f.close()
    return f.name


DRY = bool(os.environ.get("DRY_RUN"))


def edit(tag, title, body):
    if DRY:
        print(f"{tag}: would update - {title}")
        return
    path = write_body(body)
    run("gh", "release", "edit", tag, "--title", title, "--notes-file", path, check=True)
    print(f"{tag}: updated - {title}")


def publish(tag):
    notes = notes_for(tag)
    if not notes:
        print(f"::error::No release notes for {tag}: add {NOTES_DIR}/{tag}.md "
              f"or tag with a message (git tag -a {tag} -F {NOTES_DIR}/{tag}.md)")
        return 1
    title, body = notes
    rel = current(tag)
    if rel is None and DRY:
        print(f"{tag}: would create - {title}")
        return 0
    if rel is None:
        path = write_body(body)
        r = run("gh", "release", "create", tag, "--title", title,
                "--notes-file", path, "--verify-tag")
        if r.returncode == 0:
            print(f"{tag}: created - {title}")
            return 0
        # A build workflow attaching its files may have created it first.
        rel = current(tag)
        if rel is None:
            print(r.stderr, file=sys.stderr)
            return 1
    if same(rel, title, body):
        print(f"{tag}: already up to date")
    else:
        edit(tag, title, body)
    return 0


def sync():
    if not os.path.isdir(NOTES_DIR):
        return 0
    tags = sorted(f[:-3] for f in os.listdir(NOTES_DIR)
                  if f.endswith(".md") and TAG_RE.match(f[:-3]))
    for tag in tags:
        rel = current(tag)
        if rel is None:
            print(f"{tag}: no release yet - created when the tag is pushed")
            continue
        title, body = notes_for(tag)
        if same(rel, title, body):
            print(f"{tag}: up to date")
        else:
            edit(tag, title, body)
    return 0


def draft(tag):
    prev = run("git", "describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD").stdout.strip()
    rng = f"{prev}..HEAD" if prev else "HEAD"
    log = run("git", "log", "--no-merges", "--format=- %s", rng).stdout
    print(f"{tag} – <title>\n\n<one paragraph: what this release is about>\n\n"
          f"Added:\n- ...\n\nChanged:\n- ...\n\nFixed:\n- ...\n\n"
          f"Downloads: ...\n\nCompatibility: ...\n\n"
          f"<!-- commits since {prev or 'the start'}, to pick from:\n{log}-->")
    return 0


def main(argv):
    if len(argv) == 2 and argv[1] == "sync":
        return sync()
    if len(argv) == 3 and argv[1] in ("publish", "draft") and TAG_RE.match(argv[2]):
        return publish(argv[2]) if argv[1] == "publish" else draft(argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
