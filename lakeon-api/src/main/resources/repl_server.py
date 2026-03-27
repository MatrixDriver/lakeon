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
        elif req_type == "reset":
            _globals.clear()
            _globals["__builtins__"] = __builtins__
            _exec_counter = 0
            _patch_plotly()
            _patch_matplotlib()
            _emit({"id": req_id, "type": "reset_done"})

if __name__ == "__main__":
    main()
