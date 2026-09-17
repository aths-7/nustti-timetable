# -*- coding: utf-8 -*-
"""南京理工大学泰州科技学院 教务系统（正方 jsxsd）在线课表同步客户端。

已在本机对目标系统实测确认的接口事实：
    登录页      GET  https://jwgl.nustti.edu.cn/jsxsd/
    登录提交    POST https://jwgl.nustti.edu.cn/jsxsd/xk/LoginToXk
                字段：userAccount / userPassword / encoded / pwdstr1 / pwdstr2
                encoded = base64(学号) + "%%%" + base64(密码)   （见站点 conwork.js 的 encodeInp）
                失败提示位于 id="showMsg"
    验证码      GET  https://jwgl.nustti.edu.cn/jsxsd/verifycode.servlet?t=<随机数>   （image/jpeg）
    学生课表    GET/POST https://jwgl.nustti.edu.cn/jsxsd/xskb/xskb_list.do
                学期参数：xnxq01id

注意：本模块不包含任何账号凭据，账号密码全部来自界面输入。
"""

from __future__ import annotations

import base64
import re
from typing import Any, Callable, Dict, List, Optional, Tuple

import requests

import kb_parser
import store

try:  # 关闭自签名/证书链告警
    from urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except Exception:  # pragma: no cover
    pass

DEFAULT_BASE = "https://jwgl.nustti.edu.cn"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

LOGIN_PAGE_MARKERS = ("LoginToXk", "userAccount")
CAPTCHA_FIELD_RE = re.compile(
    r"<input[^>]+name\s*=\s*[\"']?(SafeCode|verifycode|captcha|yzm|checkcode|validatecode|randomcode)",
    re.I)
SHOWMSG_RE = re.compile(r"id\s*=\s*[\"']showMsg[\"'][^>]*>(.*?)</", re.I | re.S)
OPTION_RE = re.compile(r"<option[^>]*value\s*=\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</option>",
                       re.I | re.S)
SELECT_RE = re.compile(r"<select[^>]*name\s*=\s*[\"']?(xnxq01id|xnxqid|xnxq)[^\"']*[\"']?[^>]*>(.*?)</select>",
                       re.I | re.S)
FORM_RE = re.compile(r"<form[^>]*action\s*=\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</form>", re.I | re.S)
INPUT_RE = re.compile(r"<input[^>]*>", re.I)


class JwglError(Exception):
    """教务系统交互失败。"""


class LoginError(JwglError):
    """登录失败（账号/密码/验证码错误）。"""


class JwglClient:
    def __init__(self, base_url: str = DEFAULT_BASE, timeout: int = 20,
                 log: Optional[Callable[[str], None]] = None):
        self.base = (base_url or DEFAULT_BASE).rstrip("/")
        self.timeout = timeout
        self.log = log or (lambda msg: None)
        self.session = requests.Session()
        self.session.verify = False
        self.session.headers.update({
            "User-Agent": UA,
            "Accept-Language": "zh-CN,zh;q=0.9",
        })
        self.last_page = ""

    # ------------------------------------------------------------------ #
    # 基础请求
    # ------------------------------------------------------------------ #
    def _url(self, path: str) -> str:
        return path if path.startswith("http") else self.base + path

    def _get(self, path: str, **kwargs) -> requests.Response:
        kwargs.setdefault("timeout", self.timeout)
        kwargs.setdefault("allow_redirects", True)
        self.session.headers["Referer"] = self.base + "/jsxsd/"
        return self.session.get(self._url(path), **kwargs)

    def _post(self, path: str, data: Dict[str, Any], **kwargs) -> requests.Response:
        kwargs.setdefault("timeout", self.timeout)
        kwargs.setdefault("allow_redirects", True)
        self.session.headers["Referer"] = self.base + "/jsxsd/"
        return self.session.post(self._url(path), data=data, **kwargs)

    # ------------------------------------------------------------------ #
    # 工具方法
    # ------------------------------------------------------------------ #
    @staticmethod
    def encode_account(user: str, password: str) -> str:
        """复刻站点 conwork.js 的 encodeInp：base64(账号) %%% base64(密码)。"""
        enc = lambda s: base64.b64encode(s.encode("utf-8")).decode("ascii")
        return enc(user) + "%%%" + enc(password)

    @staticmethod
    def is_login_page(html: str) -> bool:
        if not html:
            return False
        return all(marker in html for marker in LOGIN_PAGE_MARKERS) and "xk/LoginToXk" in html

    @staticmethod
    def extract_message(html: str) -> str:
        m = SHOWMSG_RE.search(html or "")
        if m:
            text = re.sub(r"<[^>]+>", "", m.group(1))
            text = text.replace("&nbsp;", " ").strip()
            if text:
                return text
        return ""

    @staticmethod
    def detect_captcha_field(html: str) -> Optional[str]:
        """仅在页面中真实存在验证码输入框时才返回字段名。

        注意：登录页 JS 里总会提到 ReShowCode / verifycode.servlet，
        不能据此判定"需要验证码"，必须检测到真实的验证码输入框元素。
        """
        m = CAPTCHA_FIELD_RE.search(html or "")
        if m:
            return m.group(1)
        if re.search(r"<img[^>]+id\s*=\s*[\"']?SafeCodeImg", html or "", re.I):
            return "SafeCode"
        return None

    # ------------------------------------------------------------------ #
    # 登录
    # ------------------------------------------------------------------ #
    def fetch_captcha(self) -> bytes:
        """获取验证码图片（JPEG 字节）。"""
        last_error = None
        for path in ("/jsxsd/verifycode.servlet", "/jsxsd/verifycode", "/jsxsd/KaptchaImage"):
            try:
                resp = self._get(path, params={"t": "%.6f" % __import__("time").time()})
                ctype = (resp.headers.get("Content-Type") or "").lower()
                if resp.status_code == 200 and resp.content and (
                        "image" in ctype or resp.content[:2] == b"\xff\xd8"):
                    return resp.content
            except Exception as exc:  # pragma: no cover
                last_error = exc
        raise JwglError(f"未能获取验证码图片：{last_error or '教务系统未返回图片'}")

    def login(self, student_id: str, password: str,
              captcha_provider: Optional[Callable[[bytes], str]] = None) -> None:
        """登录教务系统。captcha_provider 用于在需要验证码时向界面索取（返回用户输入的验证码）。"""
        if not student_id or not password:
            raise LoginError("请先在「设置」中填写学号与密码")

        self.log("正在连接教务系统 ...")
        try:
            resp = self._get("/jsxsd/", allow_redirects=True)
        except requests.RequestException as exc:
            raise JwglError(f"无法访问教务系统：{exc}") from exc

        final_url = resp.url or ""
        if "cas.nustti.edu.cn" in final_url or "login_slogin" in resp.text:
            self.log("检测到统一身份认证(CAS)页面，改用通用表单登录 ...")
            self._generic_form_login(resp, student_id, password, captcha_provider)
            return

        html = resp.text
        self.last_page = html
        if not self.is_login_page(html):
            self.log("当前会话已登录，跳过登录步骤")
            return

        self.log("正在提交登录信息 ...")
        self._submit_qz_login(student_id, password, captcha_provider)
        self._assert_logged_in()

    def _submit_qz_login(self, student_id: str, password: str,
                         captcha_provider: Optional[Callable[[bytes], str]]) -> None:
        payload = {
            "userAccount": student_id,
            "userPassword": "",
            "encoded": self.encode_account(student_id, password),
            "pwdstr1": "",
            "pwdstr2": "",
        }
        resp = self._post("/jsxsd/xk/LoginToXk", payload)
        html = resp.text
        self.last_page = html
        if not self.is_login_page(html):
            return  # 登录成功

        captcha_field = self.detect_captcha_field(html)
        message = self.extract_message(html)
        if captcha_field:
            if captcha_provider is None:
                raise JwglError("教务系统要求输入验证码，但当前无法弹出输入框")
            self.log("教务系统要求输入验证码，请在弹出的窗口中填写")
            code = captcha_provider(self.fetch_captcha()) or ""
            if not code.strip():
                raise LoginError("未输入验证码，已取消登录")
            payload[captcha_field] = code.strip()
            payload["encoded"] = self.encode_account(student_id, password)
            resp = self._post("/jsxsd/xk/LoginToXk", payload)
            html = resp.text
            self.last_page = html

        if self.is_login_page(html):
            msg = self.extract_message(html)
            if "验证码" in msg:
                raise LoginError("验证码不正确，请重新同步并注意区分大小写")
            raise LoginError(f"登录失败：{msg or '账号或密码错误'}")
        self.log("登录成功")

    def _generic_form_login(self, resp: requests.Response, student_id: str, password: str,
                            captcha_provider: Optional[Callable[[bytes], str]]) -> None:
        """CAS 等标准表单登录兜底（字段名自动嗅探，验证码交给界面输入）。"""
        html = resp.text
        self.last_page = html
        form = FORM_RE.search(html)
        if not form:
            raise LoginError("统一身份认证页面结构无法识别，请改用课程表文件导入")
        action, inner = form.group(1), form.group(2)
        if action.startswith("/"):
            from urllib.parse import urljoin
            action = urljoin(resp.url, action)
        elif not action.startswith("http"):
            from urllib.parse import urljoin
            action = urljoin(resp.url, action)

        data: Dict[str, str] = {}
        user_field = pwd_field = captcha_field = None
        for tag in INPUT_RE.findall(inner):
            name = re.search(r"name\s*=\s*[\"']([^\"']+)[\"']", tag)
            if not name:
                continue
            name = name.group(1)
            value = re.search(r"value\s*=\s*[\"']([^\"']*)[\"']", tag)
            data[name] = value.group(1) if value else ""
            low = name.lower()
            if user_field is None and low in ("username", "useraccount", "account", "studentid", "loginname"):
                user_field = name
            elif pwd_field is None and "password" in low:
                pwd_field = name
            elif any(k in low for k in ("captcha", "verify", "validate", "yzm", "code")):
                captcha_field = name
        if user_field is None or pwd_field is None:
            raise LoginError("未能识别统一身份认证的用户名/密码字段")
        data[user_field] = student_id
        data[pwd_field] = password

        if captcha_field and captcha_provider is not None:
            img_url = None
            m = re.search(r"<img[^>]+src\s*=\s*[\"']([^\"']*(?:captcha|verifycode|kaptcha)[^\"']*)[\"']",
                          html, re.I)
            if m:
                from urllib.parse import urljoin
                img_url = urljoin(resp.url, m.group(1))
            if img_url:
                img = self.session.get(img_url, timeout=self.timeout, verify=False).content
                data[captcha_field] = (captcha_provider(img) or "").strip()

        result = self.session.post(action, data=data, timeout=self.timeout, verify=False)
        self.last_page = result.text
        if self.is_login_page(result.text) or "LoginToXk" in result.text:
            raise LoginError(f"统一身份认证登录失败：{self.extract_message(result.text) or '请检查账号密码'}")
        self.log("统一身份认证登录成功")

    def _assert_logged_in(self) -> None:
        try:
            resp = self._get("/jsxsd/framework/xsMain.jsp")
        except requests.RequestException as exc:
            raise JwglError(f"登录状态校验失败：{exc}") from exc
        if self.is_login_page(resp.text):
            raise LoginError("登录状态校验未通过，请重新同步")

    # ------------------------------------------------------------------ #
    # 课表
    # ------------------------------------------------------------------ #
    def _open_kb_page(self, term: Optional[str] = None) -> str:
        """打开课表页面；指定学期时按 POST → GET 顺序尝试，返回含课表表格的页面。"""
        url = "/jsxsd/xskb/xskb_list.do"
        try:
            if not term:
                return self._get(url).text
            last_html = ""
            for method, kwargs in (
                    ("post", {"data": {"xnxq01id": term, "zs": "1"}}),
                    ("post", {"data": {"xnxq01id": term}}),
                    ("get", {"params": {"xnxq01id": term}})):
                resp = (self._post if method == "post" else self._get)(url, **kwargs)
                last_html = resp.text
                if "kbtable" in last_html:
                    return last_html
            return last_html
        except requests.RequestException as exc:
            raise JwglError(f"获取课表失败：{exc}") from exc

    @staticmethod
    def parse_terms(html: str) -> List[Tuple[str, str]]:
        """从课表页面解析可选学期列表 [(value, label)]。"""
        terms: List[Tuple[str, str]] = []
        seen = set()
        for m in SELECT_RE.finditer(html or ""):
            for om in OPTION_RE.finditer(m.group(2)):
                value = (om.group(1) or "").strip()
                label = re.sub(r"<[^>]+>", "", om.group(2)).replace("&nbsp;", " ").strip()
                if value and label and value not in seen and re.search(r"\d{4}", value):
                    seen.add(value)
                    terms.append((value, label))
        if not terms:
            for om in OPTION_RE.finditer(html or ""):
                value = (om.group(1) or "").strip()
                label = re.sub(r"<[^>]+>", "", om.group(2)).strip()
                if re.fullmatch(r"\d{4}-\d{4}-\d", value) or re.search(r"\d{4}-\d{4}", value):
                    if value not in seen:
                        seen.add(value)
                        terms.append((value, label))
        return terms

    def fetch_timetable(self, term: Optional[str] = None) -> Dict[str, Any]:
        """拉取并解析课表，返回 {courses, term, terms, meta}。"""
        self.log("正在读取课表数据 ...")
        html = self._open_kb_page(term)
        self.last_page = html
        if self.is_login_page(html):
            raise LoginError("登录状态已失效，请重新同步")

        terms = self.parse_terms(html)
        parsed = kb_parser.parse_html(html)
        courses = parsed.get("courses") or []
        if not courses:
            path = store.save_debug_html(html, "kbtable")
            detail = parsed.get("meta", {}).get("error", "")
            raise JwglError(
                "未能在课表页面中识别出课程数据"
                + (f"（{detail}）" if detail else "（可能该学期无课表，或教务系统页面结构有变）")
                + (f"\n已保存页面源码：{path}" if path else ""))

        active_term = term or ""
        if not active_term:
            m = re.search(r"<option[^>]*value\s*=\s*[\"']([^\"']+)[\"'][^>]*selected", html, re.I)
            active_term = m.group(1) if m else (terms[0][0] if terms else "")
        self.log(f"课表读取完成，共 {len(courses)} 条课程记录")
        return {"courses": courses, "term": active_term, "terms": terms, "meta": parsed.get("meta", {})}
