/// Background restrictions differ by ROM; a battery exemption alone is not
/// evidence that autostart or protection from one-tap cleanup is enabled.
const captureBackgroundGuidance =
    '小米 / Redmi：允许 PennyPilot 自启动，将应用省电策略设为“无限制”，并在最近任务中锁定应用。部分系统需在“手机管家 → 设置 → 优化加速 → 锁定应用”中设置。\n\n'
    'realme / OPPO：在应用的耗电管理中允许后台活动，检查自启动设置，并在最近任务中锁定应用。入口名称可能随系统版本变化。\n\n'
    'vivo / iQOO：在应用权限中检查自启动，再到“设置 → 电池 → 后台耗电管理”允许 PennyPilot 在后台运行。入口名称随系统版本变化。\n\n'
    '设置后返回刷新状态。若系统无障碍开关仍开启但服务持续未连接，可关闭后重新开启 PennyPilot 无障碍服务；若开关已被系统关闭，则需重新授权。';

const captureBackgroundRestriction =
    '系统报告 PennyPilot 的后台运行受限。请在应用系统设置中允许后台活动，再返回确认采集服务已连接。';
