---
name: ui-ux-promax
description: Droide mobile professional-IDE UI/UX — touch, adaptive workbench, Android workstation workflows
---

## Purpose

Audit and improve Droide UI without inventing capabilities. Optimize for phones first, then tablets/foldables/hardware keyboards.

## Current assumptions

- Seven workspace destinations: File, Editor, Terminal, Git, Browser, Debug, Extensions.
- Agent chat is separate.
- Native Termux PTY/TUI is implemented.
- Android Development uses Kadb Wireless Debugging and explicit readiness states: bridge required, toolchain required, ready or unsupported.
- Build diagnostics belong in Problems and should navigate to exact source locations.

## Rules

- Material icons, no emoji as functional icons.
- Primary actions remain touch accessible; keyboard shortcuts are additive.
- Dirty/destructive operations expose review or confirmation.
- Search, Problems, symbols, Git review, debugger and Android build results preserve file/position context.
- Toolchain/provider/model copy must reflect verified runtime state, never aspiration.
- Phone-width UI must stay usable with IME, edge-to-edge and sheets.

## Checklist

- [ ] No overflow around 360dp width.
- [ ] All seven destinations remain discoverable.
- [ ] Android pairing/setup can be completed without external app instructions beyond enabling Wireless Debugging.
- [ ] Toolchain setup is not labeled zero-setup until the pinned catalog/download → license → provision path is wired and certified.
- [ ] Build/test/lint/run/log workflows expose progress and failure state clearly.
- [ ] No UI claims a toolchain/server/adapter is ready when it is missing.

---
Synchronized with the Droide professional workbench guidance on 2026-09-16.
