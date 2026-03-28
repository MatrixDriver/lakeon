#!/usr/bin/env python3
"""
DBay Notebook REPL Server
Reads JSON commands from stdin, executes Python code, writes JSON results to stdout.
Maintains a persistent globals dict across all executions.
"""
import sys
import json
import time
import traceback
import io
import contextlib
import signal
import ast
import os

_globals = {"__builtins__": __builtins__}
_exec_counter = 0
_plotly_figures = []
_mpl_images = []

def _patch_plotly():
    try:
        import plotly.io as pio
        def _patched_show(fig, *args, **kwargs):
            _plotly_figures.append(fig.to_dict())
        pio.show = _patched_show
    except ImportError:
        pass

def _patch_matplotlib():
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        def _patched_show(*args, **kwargs):
            import base64
            buf = io.BytesIO()
            plt.savefig(buf, format="png", bbox_inches="tight", dpi=100)
            buf.seek(0)
            _mpl_images.append(base64.b64encode(buf.read()).decode())
            plt.close("all")
        plt.show = _patched_show
    except ImportError:
        pass

_patch_plotly()
_patch_matplotlib()

def _emit(msg):
    sys.stdout.write(json.dumps(msg, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()

def _get_last_expr(code):
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return code, None
    if not tree.body:
        return code, None
    last = tree.body[-1]
    if isinstance(last, ast.Expr):
        lines = code.split("\n")
        last_line = last.lineno - 1
        code_before = "\n".join(lines[:last_line])
        expr_code = "\n".join(lines[last_line:last.end_lineno])
        return code_before, expr_code
    return code, None

def _execute(req_id, code):
    global _exec_counter
    _exec_counter += 1
    _plotly_figures.clear()
    _mpl_images.clear()
    start = time.time()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()

    try:
        code_body, expr_code = _get_last_expr(code)
        with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
            if code_body.strip():
                exec(code_body, _globals)
        result_val = None
        if expr_code and expr_code.strip():
            with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
                result_val = eval(expr_code.strip(), _globals)
            _globals["_"] = result_val

        out_text = stdout_buf.getvalue()
        if out_text:
            _emit({"id": req_id, "type": "stdout", "text": out_text})
        err_text = stderr_buf.getvalue()
        if err_text:
            _emit({"id": req_id, "type": "stderr", "text": err_text})
        for fig_dict in _plotly_figures:
            _emit({"id": req_id, "type": "plotly", "data": fig_dict})
        for img_b64 in _mpl_images:
            _emit({"id": req_id, "type": "image", "data": img_b64, "mime": "image/png"})
        if result_val is not None:
            result_msg = {"id": req_id, "type": "result", "text": repr(result_val)}
            try:
                import pandas as pd
                if isinstance(result_val, (pd.DataFrame, pd.Series)):
                    result_msg["html"] = result_val.to_html(max_rows=50)
                    result_msg["text"] = result_val.to_string(max_rows=20)
            except ImportError:
                pass
            _emit(result_msg)
    except Exception:
        tb = traceback.format_exc()
        _emit({"id": req_id, "type": "error", "traceback": tb})

    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_pip(req_id, args):
    """Handle %pip install <packages>"""
    import subprocess
    cmd = ["pip"] + args.split()
    start = time.time()
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if result.stdout:
            _emit({"id": req_id, "type": "stdout", "text": result.stdout})
        if result.stderr:
            _emit({"id": req_id, "type": "stderr", "text": result.stderr})
        if result.returncode != 0:
            _emit({"id": req_id, "type": "error", "traceback": f"pip exited with code {result.returncode}"})
    except subprocess.TimeoutExpired:
        _emit({"id": req_id, "type": "error", "traceback": "pip install timed out (120s)"})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_sh(req_id, cmd):
    """Handle %sh <command>"""
    import subprocess
    start = time.time()
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60)
        if result.stdout:
            _emit({"id": req_id, "type": "stdout", "text": result.stdout})
        if result.stderr:
            _emit({"id": req_id, "type": "stderr", "text": result.stderr})
        if result.returncode != 0:
            _emit({"id": req_id, "type": "error", "traceback": f"Command exited with code {result.returncode}"})
    except subprocess.TimeoutExpired:
        _emit({"id": req_id, "type": "error", "traceback": "Shell command timed out (60s)"})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_sql(req_id, sql):
    """Handle %sql <query> — connects to LAKEON_DB_CONNSTR if set"""
    start = time.time()
    try:
        connstr = os.environ.get("LAKEON_DB_CONNSTR")
        if not connstr:
            _emit({"id": req_id, "type": "error", "traceback": "No database connected. Set LAKEON_DB_CONNSTR or select a database in the toolbar."})
            _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})
            return
        import psycopg2
        import pandas as pd
        conn = psycopg2.connect(connstr)
        try:
            df = pd.read_sql(sql, conn)
            _emit({"id": req_id, "type": "result", "text": df.to_string(max_rows=20), "html": df.to_html(max_rows=50)})
            _emit({"id": req_id, "type": "stdout", "text": f"{len(df)} rows returned\n"})
            _globals["_df"] = df
        finally:
            conn.close()
    except Exception:
        _emit({"id": req_id, "type": "error", "traceback": traceback.format_exc()})
    elapsed_ms = int((time.time() - start) * 1000)
    _emit({"id": req_id, "type": "done", "duration_ms": elapsed_ms, "exec_count": _exec_counter})

def _handle_md(req_id, text):
    """Handle %md — return markdown for frontend rendering"""
    _emit({"id": req_id, "type": "markdown", "text": text})
    _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})

def _handle_vars(req_id):
    """Return current variables (name, type, short repr)"""
    skip = {"__builtins__", "_"}
    variables = []
    for name, val in sorted(_globals.items()):
        if name.startswith("_") or name in skip:
            continue
        try:
            r = repr(val)
            if len(r) > 80:
                r = r[:77] + "..."
            variables.append({"name": name, "type": type(val).__name__, "repr": r})
        except Exception:
            variables.append({"name": name, "type": type(val).__name__, "repr": "<error>"})
    _emit({"id": req_id, "type": "vars", "variables": variables})
    _emit({"id": req_id, "type": "done", "duration_ms": 0, "exec_count": _exec_counter})

def _handle_timeout(signum, frame):
    raise TimeoutError("Cell execution timed out (60s)")

def main():
    signal.signal(signal.SIGALRM, _handle_timeout)
    _emit({"type": "ready", "version": "1.0"})
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue
        req_type = req.get("type")
        req_id = req.get("id", "?")
        if req_type == "execute":
            code = req.get("code", "")
            timeout = req.get("timeout", 60)
            stripped = code.strip()
            if stripped.startswith("%pip "):
                _handle_pip(req_id, stripped[5:])
            elif stripped.startswith("%sh "):
                _handle_sh(req_id, stripped[4:])
            elif stripped.startswith("%sql "):
                _handle_sql(req_id, stripped[5:])
            elif stripped.startswith("%sql\n"):
                _handle_sql(req_id, stripped[4:].strip())
            elif stripped.startswith("%md"):
                _handle_md(req_id, stripped[3:].strip())
            else:
                signal.alarm(timeout)
                try:
                    _execute(req_id, code)
                except TimeoutError:
                    _emit({"id": req_id, "type": "error", "traceback": "TimeoutError: Cell execution timed out (60s)"})
                    _emit({"id": req_id, "type": "done", "duration_ms": timeout * 1000, "exec_count": _exec_counter})
                finally:
                    signal.alarm(0)
        elif req_type == "status":
            _emit({"id": req_id, "type": "status", "exec_count": _exec_counter})
        elif req_type == "vars":
            _handle_vars(req_id)
        elif req_type == "reset":
            _globals.clear()
            _globals["__builtins__"] = __builtins__
            _exec_counter = 0
            _patch_plotly()
            _patch_matplotlib()
            _emit({"id": req_id, "type": "reset_done"})

if __name__ == "__main__":
    main()
