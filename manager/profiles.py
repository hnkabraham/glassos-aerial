"""Observed device profiles and honest, field-level health comparisons."""


def describe(health):
    sdk = int(health["sdk"]) if str(health.get("sdk", "")).isdigit() else 0
    model = health.get("model", "")
    amazon = health.get("manufacturer", "").lower() == "amazon" or model.startswith("AFT")
    title = "Fire TV Stick 4K Max (2nd generation)" if model == "AFTKRT" else model or "Unknown device"
    return {"title": title, "platform": "Fire OS" if amazon else "Android TV / Android",
            "storage_access": "Legacy shared storage (target SDK 28)" if sdk >= 23 else "Shared storage",
            "notes": ["Home routing is reported as observed; firmware compatibility is not inferred.",
                      "Health checks read recorded settings and package state, not network traffic."]}


def compare(before, after):
    if not before:
        return []
    changes = []
    for key, label in (("model", "Model"), ("build", "Firmware"), ("sdk", "Android API"),
                       ("version", "GlassOS"), ("home", "Home handler")):
        if key in before and before.get(key) != after.get(key):
            changes.append({"label": label, "before": before[key], "after": after.get(key),
                            "attention": key in ("model", "build", "home")})
    for group, prefix in (("privacy", "Privacy setting"), ("disabled", "Package disabled"), ("instagram", "Install block")):
        for key, value in after.get(group, {}).items():
            old = before.get(group, {}).get(key)
            if old is not None and old != value:
                good = value == "0" if group == "privacy" else value is True if group == "disabled" else value == "Blocker verified"
                changes.append({"label": f"{prefix}: {key}", "before": old, "after": value, "attention": not good})
    return changes
