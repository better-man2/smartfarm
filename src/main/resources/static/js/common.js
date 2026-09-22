/* ============================================================
   智慧农业精准灌溉系统 - 公共前端逻辑
   包含：接口封装、登录守卫、提示组件、格式化工具、告警推送中心
   ============================================================ */
const App = (function () {

    const BASE = '';

    // ---------------------------------------------------------
    // 一、接口封装
    // ---------------------------------------------------------
    async function request(path, options) {
        const opts = options || {};
        const init = {
            method: opts.method || 'GET',
            credentials: 'include',
            headers: {}
        };
        if (opts.body !== undefined && opts.body !== null) {
            init.headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(opts.body);
        }

        let resp;
        try {
            resp = await fetch(BASE + path, init);
        } catch (e) {
            throw new Error('无法连接到后端服务，请确认服务已启动');
        }

        if (!resp.ok) {
            throw new Error('请求失败（HTTP ' + resp.status + '）');
        }

        const text = await resp.text();
        if (!text) {
            return { success: true, data: null };
        }
        try {
            return JSON.parse(text);
        } catch (e) {
            throw new Error('服务返回的数据格式异常');
        }
    }

    /** 发起请求并在失败时抛出错误（自动提示 message） */
    async function call(path, options) {
        const result = await request(path, options);
        if (!result.success) {
            const err = new Error(result.message || '操作失败');
            err.result = result;
            throw err;
        }
        return result;
    }

    const get = (path) => call(path);
    const post = (path, body) => call(path, { method: 'POST', body: body || {} });
    const del = (path) => call(path, { method: 'DELETE' });

    // ---------------------------------------------------------
    // 二、登录态与守卫
    // ---------------------------------------------------------
    function user() {
        try {
            return JSON.parse(sessionStorage.getItem('user') || 'null');
        } catch (e) {
            return null;
        }
    }

    function setUser(u) {
        sessionStorage.setItem('user', JSON.stringify(u));
    }

    /**
     * 页面守卫：校验登录态与角色，角色不符时跳转到对应首页。
     * 服务端同样会做角色校验，这里只是提升前端体验。
     */
    async function guard(requiredRole) {
        let info;
        try {
            const r = await get('/api/user/info');
            info = r.data;
        } catch (e) {
            window.location.href = '/index.html';
            return null;
        }
        if (!info) {
            sessionStorage.removeItem('user');
            window.location.href = '/index.html';
            return null;
        }
        setUser(info);
        if (requiredRole && info.role !== requiredRole) {
            toast('当前账号无权访问该页面，已跳转到对应工作台', 'warning');
            setTimeout(() => { window.location.href = info.home || '/index.html'; }, 900);
            return null;
        }
        return info;
    }

    async function logout() {
        if (!confirm('确定要退出登录吗？')) {
            return;
        }
        try {
            await get('/api/logout');
        } catch (e) {
            /* 忽略退出异常，本地状态照常清理 */
        }
        sessionStorage.removeItem('user');
        window.location.href = '/index.html';
    }

    // ---------------------------------------------------------
    // 三、提示与模态框
    // ---------------------------------------------------------
    function toast(message, type) {
        const kind = type || 'info';
        let box = document.getElementById('toastBox');
        if (!box) {
            box = document.createElement('div');
            box.id = 'toastBox';
            document.body.appendChild(box);
        }
        const icons = {
            success: 'fa-circle-check',
            error: 'fa-circle-exclamation',
            warning: 'fa-triangle-exclamation',
            info: 'fa-circle-info'
        };
        const el = document.createElement('div');
        el.className = 'toast-item ' + kind;
        el.innerHTML = '<i class="fas ' + (icons[kind] || icons.info) + '" style="margin-top:2px"></i>'
            + '<div>' + esc(message) + '</div>';
        box.appendChild(el);
        setTimeout(() => {
            el.style.transition = 'opacity .3s';
            el.style.opacity = '0';
            setTimeout(() => el.remove(), 320);
        }, 3600);
    }

    function openModal(id) {
        const el = document.getElementById(id);
        if (el) {
            el.classList.add('show');
        }
    }

    function closeModal(id) {
        const el = document.getElementById(id);
        if (el) {
            el.classList.remove('show');
        }
    }

    // ---------------------------------------------------------
    // 四、格式化工具
    // ---------------------------------------------------------
    /** HTML 转义：所有拼接进 innerHTML 的外部数据都必须经过此函数 */
    function esc(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function pad(n) {
        return n < 10 ? '0' + n : '' + n;
    }

    function toDate(value) {
        if (!value) {
            return null;
        }
        const d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
    }

    /** 2026-09-22 12:30 */
    function fmtDateTime(value) {
        const d = toDate(value);
        if (!d) {
            return '--';
        }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    /** 09-22 12:30 */
    function fmtShort(value) {
        const d = toDate(value);
        if (!d) {
            return '--';
        }
        return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    /** 12:30:45 */
    function fmtTime(value) {
        const d = toDate(value);
        if (!d) {
            return '--';
        }
        return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    /** 相对时间：3分钟前 */
    function timeAgo(value) {
        const d = toDate(value);
        if (!d) {
            return '--';
        }
        const diff = Date.now() - d.getTime();
        const min = Math.floor(diff / 60000);
        if (min < 1) {
            return '刚刚';
        }
        if (min < 60) {
            return min + ' 分钟前';
        }
        const hour = Math.floor(min / 60);
        if (hour < 24) {
            return hour + ' 小时前';
        }
        return Math.floor(hour / 24) + ' 天前';
    }

    function num(value, digits) {
        if (value === null || value === undefined || value === '') {
            return '--';
        }
        const n = Number(value);
        if (isNaN(n)) {
            return '--';
        }
        return n.toFixed(digits === undefined ? 1 : digits);
    }

    function orDash(value) {
        return (value === null || value === undefined || value === '') ? '--' : esc(value);
    }

    // ---------------------------------------------------------
    // 五、业务字典
    // ---------------------------------------------------------
    const LEVEL_TEXT = { HIGH: '高等级', MEDIUM: '中等级', LOW: '低等级' };
    const LEVEL_CLASS = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'gray' };

    const DEVICE_STATUS_TEXT = {
        ONLINE: '在线', OFFLINE: '离线', FAULT: '故障',
        MAINTENANCE: '维护中', UNBOUND: '未绑定'
    };
    const DEVICE_STATUS_CLASS = {
        ONLINE: 'success', OFFLINE: 'danger', FAULT: 'danger',
        MAINTENANCE: 'warning', UNBOUND: 'gray'
    };

    const DEVICE_TYPE_TEXT = {
        SOIL_MOISTURE: '土壤湿度传感器', TEMPERATURE: '温度传感器', LIGHT: '光照传感器',
        WEATHER: '农业气象站', IRRIGATION_VALVE: '灌溉电磁阀', FERTILIZER_MIXER: '水肥一体机'
    };

    const ALARM_TYPE_TEXT = {
        DEVICE_OFFLINE: '设备离线', SENSOR_ABNORMAL: '传感器异常', SOIL_DRY: '土壤干旱',
        TEMP_HIGH: '温度过高', LIGHT_ABNORMAL: '光照异常', IRRIGATION_FAIL: '灌溉失败'
    };

    const ALARM_STATUS_TEXT = { PENDING: '待处理', PROCESSING: '处理中', RESOLVED: '已解决' };
    const ALARM_STATUS_CLASS = { PENDING: 'danger', PROCESSING: 'warning', RESOLVED: 'success' };

    const IRRIGATION_STATUS_TEXT = {
        PENDING: '待执行', RUNNING: '执行中', SUCCESS: '已完成',
        FAILED: '执行失败', CANCELLED: '已取消'
    };
    const IRRIGATION_STATUS_CLASS = {
        PENDING: 'gray', RUNNING: 'info', SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'gray'
    };

    /** 土壤湿度健康状态（与后端 SensorDataService.judgeStatus 保持一致） */
    function humidityStatus(humidity, temperature) {
        if (humidity === null || humidity === undefined) {
            return { text: '无数据', cls: 'gray' };
        }
        if (humidity < 30 || (temperature !== null && temperature > 38)) {
            return { text: '严重异常', cls: 'danger' };
        }
        if (humidity < 40 || (temperature !== null && temperature > 35)) {
            return { text: '偏干', cls: 'warning' };
        }
        return { text: '适宜', cls: 'success' };
    }

    function temperatureStatus(t) {
        if (t === null || t === undefined) {
            return { text: '无数据', cls: 'gray' };
        }
        if (t > 38) return { text: '过高', cls: 'danger' };
        if (t > 35) return { text: '偏高', cls: 'warning' };
        if (t < 10) return { text: '偏低', cls: 'info' };
        return { text: '正常', cls: 'success' };
    }

    function lightStatus(l) {
        if (l === null || l === undefined) {
            return { text: '无数据', cls: 'gray' };
        }
        if (l > 60000) return { text: '过强', cls: 'warning' };
        if (l < 500) return { text: '偏暗', cls: 'info' };
        return { text: '正常', cls: 'success' };
    }

    function badge(text, color) {
        return '<span class="badge-soft bg-soft-' + (color || 'gray') + '">' + esc(text) + '</span>';
    }

    // ---------------------------------------------------------
    // 六、告警推送中心（仅高等级告警推送通知）
    // ---------------------------------------------------------
    const alarmCenter = {
        timer: null,
        onUpdate: null,
        notifiedIds: {},

        /** 请求浏览器桌面通知权限 */
        async ensurePermission() {
            if (!('Notification' in window)) {
                return false;
            }
            if (Notification.permission === 'granted') {
                return true;
            }
            if (Notification.permission === 'denied') {
                return false;
            }
            try {
                const p = await Notification.requestPermission();
                return p === 'granted';
            } catch (e) {
                return false;
            }
        },

        /**
         * 启动轮询。
         * 服务端只把 HIGH 等级且规则开启推送的告警放进队列，
         * 前端弹窗后调用 ack 确认，避免同一条告警反复提醒。
         */
        start(options) {
            const opts = options || {};
            this.onUpdate = opts.onUpdate || null;
            const interval = opts.interval || 20000;

            const tick = async () => {
                try {
                    const [unread, pending] = await Promise.all([
                        get('/api/alarm/unread'),
                        get('/api/alarm/push/pending')
                    ]);

                    if (this.onUpdate) {
                        this.onUpdate(unread.data);
                    }

                    const list = (pending.data || []).filter(a => !this.notifiedIds[a.id]);
                    if (list.length > 0) {
                        const granted = await this.ensurePermission();
                        list.forEach(alarm => {
                            this.notifiedIds[alarm.id] = true;
                            this.notifyBrowser(alarm, granted);
                            toast('【' + LEVEL_TEXT.HIGH + '告警】' + alarm.title, 'error');
                        });
                        await post('/api/alarm/push/ack', { ids: list.map(a => a.id) });
                        if (opts.onPushed) {
                            opts.onPushed(list);
                        }
                    }
                } catch (e) {
                    /* 轮询失败静默处理，避免打断用户操作 */
                }
            };

            tick();
            this.timer = setInterval(tick, interval);
        },

        notifyBrowser(alarm, granted) {
            if (!granted) {
                return;
            }
            try {
                const n = new Notification('智慧农业 · ' + LEVEL_TEXT.HIGH + '告警', {
                    body: (alarm.farmlandName ? '【' + alarm.farmlandName + '】' : '') + alarm.title + '\n' + (alarm.content || ''),
                    tag: 'smartfarm-alarm-' + alarm.id
                });
                n.onclick = () => { window.focus(); };
            } catch (e) {
                /* 部分浏览器在非 https 下会拒绝构造通知，忽略即可 */
            }
        },

        stop() {
            if (this.timer) {
                clearInterval(this.timer);
                this.timer = null;
            }
        }
    };

    // ---------------------------------------------------------
    // 七、通用渲染片段
    // ---------------------------------------------------------
    function emptyRow(colspan, text) {
        return '<tr><td colspan="' + colspan + '" class="empty-row">'
            + '<i class="fas fa-inbox" style="font-size:26px;display:block;margin-bottom:8px;opacity:.5"></i>'
            + esc(text || '暂无数据') + '</td></tr>';
    }

    function loadingRow(colspan) {
        return '<tr><td colspan="' + colspan + '" class="empty-row">'
            + '<i class="fas fa-spinner fa-spin"></i> 加载中...</td></tr>';
    }

    /** 用 Chart.js 创建折线图，已存在的实例会先销毁 */
    function lineChart(canvasId, labels, datasets, options) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) {
            return null;
        }
        const existing = Chart.getChart(canvas);
        if (existing) {
            existing.destroy();
        }
        const base = {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: { legend: { display: datasets.length > 1, position: 'top' } },
            scales: {
                y: { grid: { color: 'rgba(0,0,0,.05)' } },
                x: { grid: { display: false }, ticks: { maxTicksLimit: 10 } }
            }
        };
        const merged = Object.assign(base, options || {});
        return new Chart(canvas, {
            type: 'line',
            data: { labels: labels, datasets: datasets },
            options: merged
        });
    }

    // 暴露给各页面脚本使用
    return {
        request, call, get, post, del,
        user, setUser, guard, logout,
        toast, openModal, closeModal,
        esc, orDash, num, fmtDateTime, fmtShort, fmtTime, timeAgo,
        badge, emptyRow, loadingRow, lineChart,
        humidityStatus, temperatureStatus, lightStatus,
        LEVEL_TEXT, LEVEL_CLASS,
        DEVICE_STATUS_TEXT, DEVICE_STATUS_CLASS, DEVICE_TYPE_TEXT,
        ALARM_TYPE_TEXT, ALARM_STATUS_TEXT, ALARM_STATUS_CLASS,
        IRRIGATION_STATUS_TEXT, IRRIGATION_STATUS_CLASS,
        alarmCenter
    };
})();
