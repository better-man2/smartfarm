/* ============================================================
   管理员后台逻辑
   管理流程：设备管理 → 全局策略与告警阈值配置 → 账号权限 → 告警处理与报表导出
   ============================================================ */
const Admin = (function () {

    let me = null;
    let devices = [];
    let plots = [];
    let strategies = [];
    let rules = [];
    let accounts = [];
    let fertilizers = [];
    let deviceOptions = { types: [], statuses: [], farmlands: [] };
    let selectedDeviceIds = [];
    let selectedAlarmIds = [];
    let metrics = [];
    let currentAlarms = [];

    const VIEW_TITLES = {
        overview: '全局看板',
        devices: '设备管理',
        strategies: '灌溉策略',
        rules: '告警阈值',
        alarms: '告警记录',
        accounts: '农户账号',
        plots: '地块总览',
        fertilizer: '水肥配比',
        report: '报表导出'
    };

    // ==================================================================
    // 初始化
    // ==================================================================
    async function init() {
        me = await App.guard('admin');
        if (!me) {
            return;
        }

        document.getElementById('sideUserName').textContent = me.username;
        document.getElementById('topUserName').textContent = me.username;

        bindNav();
        startClock();

        await loadDeviceOptions();
        await loadOverview();
        await loadRules();

        App.alarmCenter.start({
            interval: 20000,
            onUpdate: updateAlarmBadge,
            onPushed: function () {
                loadAlarms();
            }
        });
    }

    function bindNav() {
        document.querySelectorAll('.nav-item').forEach(function (item) {
            item.addEventListener('click', function () {
                const view = this.dataset.view;
                document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
                this.classList.add('active');
                document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
                document.getElementById('view-' + view).classList.add('active');
                document.getElementById('pageTitle').textContent = VIEW_TITLES[view] || '';

                const loaders = {
                    overview: loadOverview,
                    devices: loadDevices,
                    strategies: loadStrategies,
                    rules: loadRules,
                    alarms: loadAlarms,
                    accounts: loadAccounts,
                    plots: loadPlots,
                    fertilizer: loadFertilizers,
                    report: null
                };
                if (loaders[view]) {
                    loaders[view]();
                }
            });
        });
    }

    function startClock() {
        const el = document.getElementById('clock');
        const tick = () => { el.textContent = new Date().toLocaleString('zh-CN', { hour12: false }); };
        tick();
        setInterval(tick, 1000);
    }

    function updateAlarmBadge(data) {
        const count = data ? (data.unread || 0) : 0;
        const high = data ? (data.unreadHigh || 0) : 0;
        const dot = document.getElementById('bellDot');
        const nav = document.getElementById('navAlarmBadge');

        if (count > 0) {
            dot.textContent = count > 99 ? '99+' : count;
            dot.classList.remove('hidden');
            nav.textContent = count > 99 ? '99+' : count;
            nav.classList.remove('hidden');
        } else {
            dot.classList.add('hidden');
            nav.classList.add('hidden');
        }
        dot.style.background = high > 0 ? '#ef4444' : '#f59e0b';
    }

    // ==================================================================
    // 1. 全局看板
    // ==================================================================
    async function loadOverview() {
        try {
            const r = await App.get('/api/stats/admin/overview');
            const d = r.data;

            renderOverviewCards(d);
            renderOverviewTable(d.farmlands || []);
            renderDeviceChart(d.deviceStatus || {});
            renderAlarmStat(d.alarms || {});
            renderRecentRecords();

            // 近 7 天用水趋势
            const h = await App.get('/api/stats/history?days=7');
            renderOverviewWaterChart((h.data.waterByDay || []));
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderOverviewCards(d) {
        const o = d.overview || {};
        const irr = d.irrigationLast30Days || {};
        const alarms = d.alarms || {};
        const byLevel = alarms.byLevel || {};

        document.getElementById('overviewCards').innerHTML = ''
            + statCard('农户数量', o.farmerCount, '户', 'blue', 'fa-users')
            + statCard('地块数量', o.farmlandCount, '块', 'green', 'fa-map-location-dot')
            + statCard('设备总数', o.deviceCount, '台', 'purple', 'fa-microchip',
                '在线率 ' + App.num(o.deviceOnlineRate) + '%')
            + statCard('待处理告警', alarms.unresolved, '条',
                alarms.unresolvedHigh > 0 ? 'red' : 'orange', 'fa-bell',
                '高等级 ' + (alarms.unresolvedHigh || 0) + ' 条')
            + statCard('近30天灌溉', irr.successCount, '次', 'blue', 'fa-droplet',
                '用水 ' + App.num(irr.totalWater) + ' m³')
            + statCard('估算节水率', App.num(irr.savingRate), '%', 'green', 'fa-leaf',
                '对比传统定时满灌（估算）')
            + statCard('总种植面积', App.num(o.totalArea), '亩', 'orange', 'fa-ruler-combined')
            + statCard('高等级告警', byLevel.HIGH || 0, '条', 'red', 'fa-triangle-exclamation',
                '近30天累计');
    }

    function statCard(label, value, unit, color, icon, hint) {
        return '<div class="stat">'
            + '<div class="stat-icon icon-' + color + '"><i class="fas ' + icon + '"></i></div>'
            + '<div class="label">' + label + '</div>'
            + '<div class="value">' + (value === null || value === undefined ? '--' : value)
            + '<span class="unit">' + unit + '</span></div>'
            + (hint ? '<div class="hint">' + hint + '</div>' : '')
            + '</div>';
    }

    function renderOverviewTable(list) {
        const tbody = document.getElementById('overviewTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(9, '暂无地块数据');
            return;
        }
        tbody.innerHTML = list.map(function (p) {
            const hs = App.humidityStatus(p.soilHumidity, p.temperature);
            const dataTag = p.status === 'NO_DATA'
                ? App.badge('无数据', 'gray')
                : (p.dataValid ? App.badge('正常', 'success') : App.badge('数据过期', 'warning'));
            return '<tr>'
                + '<td><b>' + App.esc(p.name) + '</b><div class="muted small">' + App.orDash(p.location) + '</div></td>'
                + '<td>' + App.orDash(p.ownerName) + '</td>'
                + '<td>' + App.orDash(p.cropType) + '</td>'
                + '<td>' + App.badge(p.autoIrrigation === 1 ? '自动' : '手动',
                    p.autoIrrigation === 1 ? 'purple' : 'gray') + '</td>'
                + '<td>' + App.num(p.soilHumidity) + '% ' + App.badge(hs.text, hs.cls) + '</td>'
                + '<td>' + App.num(p.temperature) + '℃</td>'
                + '<td>' + (p.onlineDeviceCount || 0) + ' / ' + (p.deviceCount || 0) + '</td>'
                + '<td>' + ((p.pendingAlarmCount || 0) > 0
                    ? '<b style="color:#b91c1c">' + p.pendingAlarmCount + '</b>' : '0') + '</td>'
                + '<td>' + dataTag + '</td>'
                + '</tr>';
        }).join('');
    }

    function renderDeviceChart(statusMap) {
        const labels = [];
        const values = [];
        const colors = {
            ONLINE: '#10b981', OFFLINE: '#ef4444', FAULT: '#dc2626',
            MAINTENANCE: '#f59e0b', UNBOUND: '#9ca3af'
        };
        const bg = [];
        Object.keys(statusMap).forEach(function (k) {
            labels.push(App.DEVICE_STATUS_TEXT[k] || k);
            values.push(statusMap[k]);
            bg.push(colors[k] || '#9ca3af');
        });

        const canvas = document.getElementById('deviceChart');
        const existing = Chart.getChart(canvas);
        if (existing) {
            existing.destroy();
        }
        if (labels.length === 0) {
            labels.push('暂无设备');
            values.push(1);
            bg.push('#e5e7eb');
        }
        new Chart(canvas, {
            type: 'doughnut',
            data: { labels: labels, datasets: [{ data: values, backgroundColor: bg, borderWidth: 0 }] },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { position: 'right', labels: { boxWidth: 12, font: { size: 12 } } } }
            }
        });
    }

    function renderAlarmStat(alarms) {
        const byLevel = alarms.byLevel || {};
        const byType = alarms.byType || {};
        const typeText = App.ALARM_TYPE_TEXT;

        let html = '<div class="mb-2">'
            + App.badge('高等级 ' + (byLevel.HIGH || 0), 'danger') + ' '
            + App.badge('中等级 ' + (byLevel.MEDIUM || 0), 'warning') + ' '
            + App.badge('低等级 ' + (byLevel.LOW || 0), 'gray')
            + '</div>';

        const keys = Object.keys(byType);
        if (keys.length === 0) {
            html += '<div class="muted small">近 30 天暂无告警</div>';
        } else {
            html += '<table class="data-table" style="font-size:13px"><tbody>'
                + keys.map(k => '<tr><td>' + (typeText[k] || k) + '</td><td class="text-right"><b>'
                    + byType[k] + '</b> 条</td></tr>').join('')
                + '</tbody></table>';
        }
        html += '<div class="muted small mt-2">共 ' + (alarms.total || 0) + ' 条，未解决 '
            + (alarms.unresolved || 0) + ' 条</div>';
        document.getElementById('alarmStatBox').innerHTML = html;
    }

    async function renderRecentRecords() {
        try {
            const r = await App.get('/api/stats/recent-records?limit=8');
            const list = r.data || [];
            const box = document.getElementById('recentRecords');
            if (list.length === 0) {
                box.innerHTML = '<div class="muted small">暂无灌溉记录</div>';
                return;
            }
            box.innerHTML = list.map(function (x) {
                return '<div style="padding:7px 0;border-bottom:1px solid #f1f3f5">'
                    + '<div class="d-flex justify-content-between align-items-center">'
                    + '<span><b>' + App.esc(x.farmlandName) + '</b> '
                    + App.badge(x.triggerType === 'AUTO' ? '自动' : '手动',
                        x.triggerType === 'AUTO' ? 'purple' : 'info') + '</span>'
                    + '<span>' + App.num(x.waterAmount) + ' m³ '
                    + App.badge(App.IRRIGATION_STATUS_TEXT[x.status] || x.status,
                        App.IRRIGATION_STATUS_CLASS[x.status] || 'gray') + '</span>'
                    + '</div>'
                    + '<div class="muted" style="font-size:12px">' + App.fmtDateTime(x.createdAt) + '</div>'
                    + '</div>';
            }).join('');
        } catch (e) {
            /* 忽略 */
        }
    }

    function renderOverviewWaterChart(list) {
        App.lineChart('overviewWaterChart', list.map(x => x.day.substring(5)), [{
            label: '每日用水量 (m³)',
            data: list.map(x => x.totalWater),
            borderColor: '#667eea',
            backgroundColor: 'rgba(102,126,234,.14)',
            borderWidth: 2.5,
            fill: true,
            tension: .3
        }]);
    }

    // ==================================================================
    // 2. 设备管理
    // ==================================================================
    async function loadDeviceOptions() {
        try {
            const r = await App.get('/api/device/options');
            deviceOptions = r.data;

            fillSelect('filterType', deviceOptions.types, '全部类型');
            fillSelect('filterStatus', deviceOptions.statuses, '全部状态');
            fillSelect('filterFarmland', deviceOptions.farmlands.map(f => ({
                value: f.id, label: f.name + '（' + f.ownerName + '）'
            })), '全部地块');

            fillSelect('dType', deviceOptions.types, null);
            fillSelect('dFarmland', deviceOptions.farmlands.map(f => ({
                value: f.id, label: f.name + '（' + f.ownerName + '）'
            })), '暂不绑定（存入仓库）');

            fillSelect('batchFarmland', deviceOptions.farmlands.map(f => ({
                value: f.id, label: '绑定到：' + f.name
            })), '选择目标地块');
            fillSelect('batchStatus', deviceOptions.statuses, '选择目标状态');

            // 策略与配比表单里的地块下拉
            plots = deviceOptions.farmlands.map(f => ({ id: f.id, name: f.name, username: f.ownerName }));
            fillSelect('sFarmland', plots.map(p => ({ value: p.id, label: p.name })), '请选择地块');
            fillSelect('fFarmland', plots.map(p => ({ value: p.id, label: p.name })), '通用方案（所有地块可用）');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function fillSelect(id, options, placeholder) {
        const sel = document.getElementById(id);
        if (!sel) {
            return;
        }
        let html = placeholder ? '<option value="">' + App.esc(placeholder) + '</option>' : '';
        html += (options || []).map(function (o) {
            const value = o.value !== undefined ? o.value : o;
            const label = o.label !== undefined ? o.label : o;
            return '<option value="' + value + '">' + App.esc(label) + '</option>';
        }).join('');
        sel.innerHTML = html;
    }

    async function loadDevices() {
        try {
            const params = [];
            const t = document.getElementById('filterType').value;
            const s = document.getElementById('filterStatus').value;
            const f = document.getElementById('filterFarmland').value;
            const k = document.getElementById('filterKeyword').value.trim();
            if (t) params.push('deviceType=' + t);
            if (s) params.push('status=' + s);
            if (f) params.push('farmlandId=' + f);
            if (k) params.push('keyword=' + encodeURIComponent(k));

            const r = await App.get('/api/device/list' + (params.length ? '?' + params.join('&') : ''));
            devices = r.data || [];
            renderDeviceTable(devices);

            const stat = await App.get('/api/device/statistics');
            const sd = stat.data;
            document.getElementById('deviceSummary').innerHTML =
                '共 <b>' + sd.total + '</b> 台设备，在线 <b>' + sd.byStatus.ONLINE
                + '</b> 台，在线率 <b>' + App.num(sd.onlineRate) + '%</b>，'
                + '异常 <b style="color:#b91c1c">' + sd.abnormalCount + '</b> 台';
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderDeviceTable(list) {
        const tbody = document.getElementById('deviceTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(9, '没有符合条件的设备');
            return;
        }
        tbody.innerHTML = list.map(function (d) {
            const stale = d.offlineMinutes !== null && d.offlineMinutes !== undefined && d.offlineMinutes > 30;
            return '<tr>'
                + '<td><input type="checkbox" class="dev-check" value="' + d.id + '" onchange="Admin.syncSelection()"></td>'
                + '<td><b>' + App.esc(d.deviceCode) + '</b></td>'
                + '<td>' + App.esc(d.deviceName) + '</td>'
                + '<td>' + (App.DEVICE_TYPE_TEXT[d.deviceType] || d.deviceType) + '</td>'
                + '<td>' + (d.farmlandName ? App.esc(d.farmlandName) : '<span class="muted">未绑定</span>') + '</td>'
                + '<td class="small muted">' + App.orDash(d.installLocation) + '</td>'
                + '<td>' + App.badge(App.DEVICE_STATUS_TEXT[d.status] || d.status,
                    App.DEVICE_STATUS_CLASS[d.status] || 'gray') + '</td>'
                + '<td class="small' + (stale ? '" style="color:#b45309' : ' muted') + '">'
                + (d.lastOnlineTime ? App.timeAgo(d.lastOnlineTime) : '无记录')
                + (stale ? ' <i class="fas fa-triangle-exclamation"></i>' : '') + '</td>'
                + '<td><div class="btn-row">'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.openDeviceModal(' + d.id + ')">编辑</button>'
                + (d.farmlandId
                    ? '<button class="btn btn-outline btn-sm" onclick="Admin.bindDevice(' + d.id + ', null)">解绑</button>'
                    : '<button class="btn btn-outline btn-sm" onclick="Admin.showBindPrompt(' + d.id + ')">绑定</button>')
                + '<button class="btn btn-outline btn-sm" onclick="Admin.heartbeat(' + d.id + ')">心跳</button>'
                + '<button class="btn btn-danger-outline btn-sm" onclick="Admin.deleteDevice(' + d.id + ')">删除</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
        syncSelection();
    }

    function toggleAll(checkbox) {
        document.querySelectorAll('.dev-check').forEach(function (c) {
            c.checked = checkbox.checked;
        });
        syncSelection();
    }

    function syncSelection() {
        selectedDeviceIds = Array.from(document.querySelectorAll('.dev-check'))
            .filter(c => c.checked).map(c => Number(c.value));
        document.getElementById('selectedCount').textContent = selectedDeviceIds.length;
    }

    function showBindPrompt(deviceId) {
        const options = plots.map((p, i) => (i + 1) + '. ' + p.name + '（' + p.username + '）').join('\n');
        const input = prompt('请输入要绑定的地块序号：\n' + options);
        if (!input) {
            return;
        }
        const index = Number(input) - 1;
        if (isNaN(index) || index < 0 || index >= plots.length) {
            App.toast('输入的序号无效', 'warning');
            return;
        }
        bindDevice(deviceId, plots[index].id);
    }

    async function bindDevice(deviceId, farmlandId) {
        try {
            const url = '/api/device/' + deviceId + '/bind' + (farmlandId ? '?farmlandId=' + farmlandId : '');
            const r = await App.post(url);
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function batchBind() {
        if (selectedDeviceIds.length === 0) {
            App.toast('请先勾选要操作的设备', 'warning');
            return;
        }
        const farmlandId = document.getElementById('batchFarmland').value;
        if (!farmlandId) {
            App.toast('请先选择目标地块', 'warning');
            return;
        }
        try {
            const r = await App.post('/api/device/batch/bind', {
                deviceIds: selectedDeviceIds, farmlandId: Number(farmlandId)
            });
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function batchUnbind() {
        if (selectedDeviceIds.length === 0) {
            App.toast('请先勾选要操作的设备', 'warning');
            return;
        }
        if (!confirm('确定要将选中的 ' + selectedDeviceIds.length + ' 台设备解绑吗？')) {
            return;
        }
        try {
            const r = await App.post('/api/device/batch/unbind', { deviceIds: selectedDeviceIds });
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function batchStatus() {
        if (selectedDeviceIds.length === 0) {
            App.toast('请先勾选要操作的设备', 'warning');
            return;
        }
        const status = document.getElementById('batchStatus').value;
        if (!status) {
            App.toast('请先选择目标状态', 'warning');
            return;
        }
        try {
            const r = await App.post('/api/device/batch/status', {
                deviceIds: selectedDeviceIds, status: status
            });
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function openDeviceModal(id) {
        const modalId = 'deviceModal';
        document.getElementById('dId').value = '';
        document.getElementById('dCode').value = '';
        document.getElementById('dName').value = '';
        document.getElementById('dLocation').value = '';
        document.getElementById('dFirmware').value = 'v1.2.0';
        document.getElementById('dRemark').value = '';
        document.getElementById('dFarmland').value = '';
        document.getElementById('deviceModalTitle').textContent = '新增设备';
        document.getElementById('dCode').disabled = false;

        if (id) {
            const d = devices.find(x => x.id === id);
            if (d) {
                document.getElementById('dId').value = d.id;
                document.getElementById('dCode').value = d.deviceCode;
                document.getElementById('dName').value = d.deviceName;
                document.getElementById('dType').value = d.deviceType;
                document.getElementById('dFarmland').value = d.farmlandId || '';
                document.getElementById('dLocation').value = d.installLocation || '';
                document.getElementById('dFirmware').value = d.firmware || '';
                document.getElementById('dRemark').value = d.remark || '';
                document.getElementById('deviceModalTitle').textContent = '编辑设备';
                // 设备编号是数据上报的标识，创建后不允许修改
                document.getElementById('dCode').disabled = true;
            }
        }
        App.openModal(modalId);
    }

    async function saveDevice() {
        const id = document.getElementById('dId').value;
        const body = {
            deviceName: document.getElementById('dName').value.trim(),
            deviceType: document.getElementById('dType').value,
            installLocation: document.getElementById('dLocation').value.trim(),
            firmware: document.getElementById('dFirmware').value.trim(),
            remark: document.getElementById('dRemark').value.trim()
        };
        const farmlandVal = document.getElementById('dFarmland').value;
        body.farmlandId = farmlandVal ? Number(farmlandVal) : null;

        if (!body.deviceName) {
            App.toast('请填写设备名称', 'warning');
            return;
        }

        try {
            let r;
            if (id) {
                body.id = Number(id);
                r = await App.post('/api/device/update', body);
            } else {
                body.deviceCode = document.getElementById('dCode').value.trim();
                if (!body.deviceCode) {
                    App.toast('请填写设备编号', 'warning');
                    return;
                }
                r = await App.post('/api/device/add', body);
            }
            App.toast(r.message, 'success');
            App.closeModal('deviceModal');
            await loadDevices();
            await loadOverviewIfActive();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function deleteDevice(id) {
        const d = devices.find(x => x.id === id);
        if (!confirm('确定要删除设备「' + (d ? d.deviceName : id) + '」吗？此操作不可恢复。')) {
            return;
        }
        try {
            const r = await App.del('/api/device/delete/' + id);
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function heartbeat(id) {
        try {
            const r = await App.post('/api/device/' + id + '/heartbeat');
            App.toast(r.message, 'success');
            await loadDevices();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 3. 灌溉策略
    // ==================================================================
    async function loadStrategies() {
        try {
            const [s, p] = await Promise.all([
                App.get('/api/strategy/list'),
                App.get('/api/farmland/all')
            ]);
            strategies = s.data || [];
            plots = p.data || [];

            renderStrategyTable(strategies);
            renderPlotStrategyTable(plots);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderStrategyTable(list) {
        const tbody = document.getElementById('strategyTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(11, '暂无策略，请先新增全局策略');
            return;
        }
        tbody.innerHTML = list.map(function (s) {
            return '<tr>'
                + '<td><b>' + App.esc(s.name) + '</b></td>'
                + '<td>' + (s.isGlobal === 1
                    ? App.badge('全局', 'purple')
                    : App.badge(App.orDash(s.farmlandName), 'info')) + '</td>'
                + '<td>' + App.num(s.soilHumidityMin) + '%</td>'
                + '<td>' + App.num(s.targetHumidity) + '%</td>'
                + '<td>' + App.num(s.temperatureMax) + '℃</td>'
                + '<td>' + App.num(s.lightIntensityMax, 0) + '</td>'
                + '<td>' + App.num(s.irrigationAmount) + ' m³</td>'
                + '<td>' + s.durationMinutes + ' 分钟</td>'
                + '<td class="small">' + App.esc(s.allowedStartTime) + '~' + App.esc(s.allowedEndTime) + '</td>'
                + '<td>' + App.badge(s.enabled === 1 ? '启用' : '停用',
                    s.enabled === 1 ? 'success' : 'gray') + '</td>'
                + '<td><div class="btn-row">'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.openStrategyModal(' + s.id + ')">编辑</button>'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.toggleStrategy(' + s.id + ','
                + (s.enabled === 1 ? 0 : 1) + ')">' + (s.enabled === 1 ? '停用' : '启用') + '</button>'
                + '<button class="btn btn-danger-outline btn-sm" onclick="Admin.deleteStrategy(' + s.id + ')">删除</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
    }

    function renderPlotStrategyTable(list) {
        const tbody = document.getElementById('plotStrategyTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(5, '暂无地块');
            return;
        }

        tbody.innerHTML = list.map(function (p) {
            // 按地块当前绑定生成下拉选项，直接标出 selected，避免事后做字符串替换
            let options = '<option value=""'
                + (p.strategyId ? '' : ' selected') + '>使用全局策略</option>';
            options += strategies.map(function (s) {
                return '<option value="' + s.id + '"'
                    + (p.strategyId === s.id ? ' selected' : '') + '>'
                    + App.esc(s.name) + (s.isGlobal === 1 ? '（全局）' : '') + '</option>';
            }).join('');

            return '<tr>'
                + '<td><b>' + App.esc(p.name) + '</b></td>'
                + '<td>' + App.orDash(p.username) + '</td>'
                + '<td>' + App.badge(p.autoIrrigation === 1 ? '自动模式' : '手动模式',
                    p.autoIrrigation === 1 ? 'purple' : 'gray') + '</td>'
                + '<td>' + (p.strategyName ? App.esc(p.strategyName) : '<span class="muted">全局策略</span>') + '</td>'
                + '<td><div class="d-flex gap-2">'
                + '<select class="form-select" id="plotStrategy-' + p.id + '" style="width:auto">'
                + options + '</select>'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.applyStrategy(' + p.id + ')">应用</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
    }

    async function applyStrategy(farmlandId) {
        const sel = document.getElementById('plotStrategy-' + farmlandId);
        const val = sel.value;
        try {
            const r = await App.post('/api/strategy/apply', {
                farmlandId: farmlandId,
                strategyId: val ? Number(val) : null
            });
            App.toast(r.message, 'success');
            await loadStrategies();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function openStrategyModal(id) {
        document.getElementById('sId').value = '';
        document.getElementById('sName').value = '';
        document.getElementById('sScope').value = 'global';
        document.getElementById('sHumidityMin').value = '40';
        document.getElementById('sTarget').value = '65';
        document.getElementById('sTempMax').value = '38';
        document.getElementById('sLightMax').value = '60000';
        document.getElementById('sWater').value = '15';
        document.getElementById('sDuration').value = '10';
        document.getElementById('sStart').value = '06:00';
        document.getElementById('sEnd').value = '20:00';
        document.getElementById('sEnabled').value = '1';
        document.getElementById('strategyModalTitle').textContent = '新增灌溉策略';
        onScopeChange();

        if (id) {
            const s = strategies.find(x => x.id === id);
            if (s) {
                document.getElementById('sId').value = s.id;
                document.getElementById('sName').value = s.name;
                document.getElementById('sScope').value = s.isGlobal === 1 ? 'global' : 'plot';
                document.getElementById('sFarmland').value = s.farmlandId || '';
                document.getElementById('sHumidityMin').value = s.soilHumidityMin;
                document.getElementById('sTarget').value = s.targetHumidity;
                document.getElementById('sTempMax').value = s.temperatureMax;
                document.getElementById('sLightMax').value = s.lightIntensityMax;
                document.getElementById('sWater').value = s.irrigationAmount;
                document.getElementById('sDuration').value = s.durationMinutes;
                document.getElementById('sStart').value = s.allowedStartTime;
                document.getElementById('sEnd').value = s.allowedEndTime;
                document.getElementById('sEnabled').value = String(s.enabled);
                document.getElementById('strategyModalTitle').textContent = '编辑灌溉策略';
                onScopeChange();
            }
        }
        App.openModal('strategyModal');
    }

    function onScopeChange() {
        const scope = document.getElementById('sScope').value;
        document.getElementById('sFarmlandField').style.display = scope === 'plot' ? 'block' : 'none';
    }

    async function saveStrategy() {
        const scope = document.getElementById('sScope').value;
        const body = {
            name: document.getElementById('sName').value.trim(),
            isGlobal: scope === 'global' ? 1 : 0,
            farmlandId: scope === 'plot' ? Number(document.getElementById('sFarmland').value) : null,
            soilHumidityMin: Number(document.getElementById('sHumidityMin').value),
            targetHumidity: Number(document.getElementById('sTarget').value),
            temperatureMax: Number(document.getElementById('sTempMax').value),
            lightIntensityMax: Number(document.getElementById('sLightMax').value),
            irrigationAmount: Number(document.getElementById('sWater').value),
            durationMinutes: Number(document.getElementById('sDuration').value),
            allowedStartTime: document.getElementById('sStart').value.trim(),
            allowedEndTime: document.getElementById('sEnd').value.trim(),
            enabled: Number(document.getElementById('sEnabled').value)
        };
        const id = document.getElementById('sId').value;
        if (id) {
            body.id = Number(id);
        }
        if (scope === 'plot' && !body.farmlandId) {
            App.toast('请选择要应用的地块', 'warning');
            return;
        }

        try {
            const r = await App.post('/api/strategy/save', body);
            App.toast(r.message, 'success');
            App.closeModal('strategyModal');
            await loadStrategies();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function toggleStrategy(id, enabled) {
        try {
            const r = await App.post('/api/strategy/' + id + '/toggle?enabled=' + enabled);
            App.toast(r.message, 'success');
            await loadStrategies();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function deleteStrategy(id) {
        const s = strategies.find(x => x.id === id);
        if (!confirm('确定要删除策略「' + (s ? s.name : id) + '」吗？\n使用该策略的地块将自动改回全局策略。')) {
            return;
        }
        try {
            const r = await App.del('/api/strategy/delete/' + id);
            App.toast(r.message, 'success');
            await loadStrategies();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 4. 告警阈值规则
    // ==================================================================
    async function loadRules() {
        try {
            const [r, m] = await Promise.all([
                App.get('/api/alarm/rule/list'),
                App.get('/api/alarm/rule/metrics')
            ]);
            rules = r.data || [];
            metrics = m.data || [];

            fillSelect('rlMetric', metrics.map(x => ({ value: x.value, label: x.label })), null);
            renderRuleTable(rules);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderRuleTable(list) {
        const tbody = document.getElementById('ruleTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(9, '暂无阈值规则');
            return;
        }
        tbody.innerHTML = list.map(function (r) {
            const unit = unitOf(r.metric);
            const metricLabel = (metrics.find(m => m.value === r.metric) || {}).label || r.metric;
            const pushTag = r.pushEnabled === 1
                ? App.badge('推送通知', 'danger')
                : App.badge('仅记录', 'gray');
            return '<tr>'
                + '<td><b>' + App.esc(r.ruleName) + '</b></td>'
                + '<td>' + App.esc(metricLabel) + '</td>'
                + '<td>' + (r.compareOp === 'LT' ? '小于 ' : '大于 ')
                + App.num(r.threshold) + unit + '</td>'
                + '<td>' + App.badge(App.LEVEL_TEXT[r.alarmLevel] || r.alarmLevel,
                    App.LEVEL_CLASS[r.alarmLevel] || 'gray') + '</td>'
                + '<td>' + pushTag + '</td>'
                + '<td>' + (r.farmlandName ? App.esc(r.farmlandName) : '全局') + '</td>'
                + '<td>' + App.badge(r.enabled === 1 ? '启用' : '停用',
                    r.enabled === 1 ? 'success' : 'gray') + '</td>'
                + '<td class="small muted">' + App.orDash(r.description) + '</td>'
                + '<td><div class="btn-row">'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.openRuleModal(' + r.id + ')">编辑</button>'
                + '<button class="btn btn-danger-outline btn-sm" onclick="Admin.deleteRule(' + r.id + ')">删除</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
    }

    function unitOf(metric) {
        const m = metrics.find(x => x.value === metric);
        return m ? m.unit : '';
    }

    function openRuleModal(id) {
        document.getElementById('rId').value = '';
        document.getElementById('rlName').value = '';
        document.getElementById('rlMetric').value = 'SOIL_HUMIDITY';
        document.getElementById('rlOp').value = 'LT';
        document.getElementById('rlThreshold').value = '30';
        document.getElementById('rlLevel').value = 'HIGH';
        document.getElementById('rlPush').value = '1';
        document.getElementById('rlDesc').value = '';
        document.getElementById('ruleModalTitle').textContent = '新增告警阈值规则';
        onMetricChange();
        onLevelChange();

        if (id) {
            const r = rules.find(x => x.id === id);
            if (r) {
                document.getElementById('rId').value = r.id;
                document.getElementById('rlName').value = r.ruleName;
                document.getElementById('rlMetric').value = r.metric;
                document.getElementById('rlOp').value = r.compareOp;
                document.getElementById('rlThreshold').value = r.threshold;
                document.getElementById('rlLevel').value = r.alarmLevel;
                document.getElementById('rlPush').value = String(r.pushEnabled);
                document.getElementById('rlDesc').value = r.description || '';
                document.getElementById('ruleModalTitle').textContent = '编辑告警阈值规则';
                onMetricChange();
                onLevelChange();
            }
        }
        App.openModal('ruleModal');
    }

    function onMetricChange() {
        const metric = document.getElementById('rlMetric').value;
        const m = metrics.find(x => x.value === metric);
        if (m) {
            document.getElementById('rlUnitTip').textContent = '单位：' + m.unit;
            // 切换到新指标时套用推荐比较方式，减少配置出错
            if (!document.getElementById('rId').value) {
                document.getElementById('rlOp').value = m.defaultOp;
            }
        }
    }

    /** 等级变化时同步推送选项：非高等级强制不可推送 */
    function onLevelChange() {
        const level = document.getElementById('rlLevel').value;
        const pushSel = document.getElementById('rlPush');
        const tip = document.getElementById('rlPushTip');

        if (level === 'HIGH') {
            pushSel.disabled = false;
            tip.textContent = '高等级故障/异常建议开启推送，使农户能及时收到通知';
        } else {
            pushSel.value = '0';
            pushSel.disabled = true;
            tip.textContent = '中/低等级告警不推送通知，仅在页面记录（规则约束）';
        }
    }

    async function saveRule() {
        const body = {
            ruleName: document.getElementById('rlName').value.trim(),
            metric: document.getElementById('rlMetric').value,
            compareOp: document.getElementById('rlOp').value,
            threshold: Number(document.getElementById('rlThreshold').value),
            alarmLevel: document.getElementById('rlLevel').value,
            pushEnabled: Number(document.getElementById('rlPush').value),
            description: document.getElementById('rlDesc').value.trim()
        };
        const id = document.getElementById('rId').value;
        if (id) {
            body.id = Number(id);
        }
        try {
            const r = await App.post('/api/alarm/rule/save', body);
            App.toast(r.message, 'success');
            App.closeModal('ruleModal');
            await loadRules();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function deleteRule(id) {
        const r = rules.find(x => x.id === id);
        if (!confirm('确定要删除规则「' + (r ? r.ruleName : id) + '」吗？')) {
            return;
        }
        try {
            const res = await App.del('/api/alarm/rule/delete/' + id);
            App.toast(res.message, 'success');
            await loadRules();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 5. 告警记录
    // ==================================================================
    async function loadAlarms() {
        try {
            const params = [];
            const l = document.getElementById('aLevel').value;
            const s = document.getElementById('aStatus').value;
            const t = document.getElementById('aType').value;
            if (l) params.push('level=' + l);
            if (s) params.push('status=' + s);
            if (t) params.push('alarmType=' + t);
            params.push('limit=200');

            const r = await App.get('/api/alarm/list?' + params.join('&'));
            currentAlarms = r.data || [];
            renderAlarmList(currentAlarms);

            const un = await App.get('/api/alarm/unread');
            document.getElementById('alarmSummary').innerHTML =
                '当前列表 <b>' + currentAlarms.length + '</b> 条；待处理 <b>' + un.data.unread
                + '</b> 条（高等级 <b style="color:#b91c1c">' + un.data.unreadHigh + '</b> 条）';
            updateAlarmBadge(un.data);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderAlarmList(list) {
        const box = document.getElementById('alarmList');
        if (list.length === 0) {
            box.innerHTML = '<div class="muted small text-center" style="padding:30px 0">'
                + '<i class="fas fa-circle-check" style="font-size:26px;display:block;margin-bottom:8px;opacity:.5"></i>'
                + '暂无符合条件的告警</div>';
            return;
        }
        box.innerHTML = list.map(function (a) {
            const pushTag = a.pushStatus === 1
                ? App.badge('已推送通知', 'danger')
                : App.badge('仅记录', 'gray');
            return '<div class="alarm-item level-' + a.alarmLevel + '">'
                + '<div class="alarm-head">'
                + '<input type="checkbox" class="alarm-check" value="' + a.id
                + '" onchange="Admin.syncAlarmSelection()">'
                + App.badge(App.LEVEL_TEXT[a.alarmLevel] || a.alarmLevel,
                    App.LEVEL_CLASS[a.alarmLevel] || 'gray')
                + '<span class="alarm-title">' + App.esc(a.title) + '</span>'
                + App.badge(App.ALARM_TYPE_TEXT[a.alarmType] || a.alarmType, 'gray')
                + pushTag
                + App.badge(App.ALARM_STATUS_TEXT[a.status] || a.status,
                    App.ALARM_STATUS_CLASS[a.status] || 'gray')
                + '</div>'
                + '<div class="alarm-content">' + App.esc(a.content) + '</div>'
                + '<div class="alarm-foot">'
                + '<span><i class="fas fa-clock me-1"></i>' + App.fmtDateTime(a.createdAt)
                + '（' + App.timeAgo(a.createdAt) + '）</span>'
                + (a.farmlandName ? '<span><i class="fas fa-map-location-dot me-1"></i>' + App.esc(a.farmlandName) + '</span>' : '')
                + (a.deviceCode ? '<span><i class="fas fa-microchip me-1"></i>' + App.esc(a.deviceName)
                    + ' (' + App.esc(a.deviceCode) + ')</span>' : '')
                + (a.handledByName ? '<span><i class="fas fa-user-check me-1"></i>' + App.esc(a.handledByName)
                    + ' 于 ' + App.fmtDateTime(a.handledTime) + ' 处理</span>' : '')
                + '<span class="ms-auto">'
                + (a.status !== 'RESOLVED'
                    ? '<button class="btn btn-outline btn-sm" onclick="Admin.handleAlarm(' + a.id + ',\'PROCESSING\')">处理中</button> '
                    + '<button class="btn btn-primary btn-sm" onclick="Admin.handleAlarm(' + a.id + ',\'RESOLVED\')">标记已解决</button>'
                    : '<span class="muted small">处理备注：' + App.orDash(a.handleRemark) + '</span>')
                + '</span></div></div>';
        }).join('');
        syncAlarmSelection();
    }

    function syncAlarmSelection() {
        selectedAlarmIds = Array.from(document.querySelectorAll('.alarm-check'))
            .filter(c => c.checked).map(c => Number(c.value));
        document.getElementById('selectedAlarmCount').textContent = selectedAlarmIds.length;
    }

    async function handleAlarm(id, status) {
        let remark = '';
        if (status === 'RESOLVED') {
            remark = prompt('请填写处理说明：', '已排查处理') || '';
        }
        try {
            const r = await App.post('/api/alarm/' + id + '/handle', { status: status, remark: remark });
            App.toast(r.message, 'success');
            await loadAlarms();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function batchHandleAlarm(status) {
        if (selectedAlarmIds.length === 0) {
            App.toast('请先勾选要处理的告警', 'warning');
            return;
        }
        let remark = '';
        if (status === 'RESOLVED') {
            remark = prompt('请填写批量处理说明：', '已批量处理') || '';
        }
        try {
            const r = await App.post('/api/alarm/batch-handle', {
                ids: selectedAlarmIds, status: status, remark: remark
            });
            App.toast(r.message, 'success');
            await loadAlarms();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 6. 农户账号
    // ==================================================================
    async function loadAccounts() {
        try {
            const r = await App.get('/api/admin/user/list');
            accounts = r.data || [];
            renderAccountTable(accounts);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderAccountTable(list) {
        const tbody = document.getElementById('accountTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(8, '暂无账号');
            return;
        }
        tbody.innerHTML = list.map(function (u) {
            const isSelf = me && u.id === me.id;
            return '<tr>'
                + '<td>' + u.id + '</td>'
                + '<td><b>' + App.esc(u.username) + '</b>'
                + (isSelf ? ' ' + App.badge('当前登录', 'info') : '') + '</td>'
                + '<td>' + App.badge(u.role === 'admin' ? '管理员' : '农户',
                    u.role === 'admin' ? 'purple' : 'info') + '</td>'
                + '<td>' + App.orDash(u.phone) + '</td>'
                + '<td>' + (u.role === 'farmer' ? (u.farmlandCount || 0) + ' 块' : '--') + '</td>'
                + '<td>' + App.badge(u.status === 1 ? '启用' : '已禁用',
                    u.status === 1 ? 'success' : 'danger') + '</td>'
                + '<td class="small muted">' + App.fmtDateTime(u.createdAt) + '</td>'
                + '<td><div class="btn-row">'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.openAccountModal(' + u.id + ')">编辑</button>'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.toggleAccount(' + u.id + ','
                + (u.status === 1 ? 0 : 1) + ')"' + (isSelf ? ' disabled' : '') + '>'
                + (u.status === 1 ? '禁用' : '启用') + '</button>'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.resetPassword(' + u.id + ')">重置密码</button>'
                + '<button class="btn btn-danger-outline btn-sm" onclick="Admin.deleteAccount(' + u.id + ')"'
                + (isSelf ? ' disabled' : '') + '>删除</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
    }

    function openAccountModal(id) {
        document.getElementById('acId').value = '';
        document.getElementById('acUsername').value = '';
        document.getElementById('acPassword').value = '';
        document.getElementById('acRole').value = 'farmer';
        document.getElementById('acPhone').value = '';
        document.getElementById('accountModalTitle').textContent = '新增账号';
        document.getElementById('acUsername').disabled = false;

        if (id) {
            const u = accounts.find(x => x.id === id);
            if (u) {
                document.getElementById('acId').value = u.id;
                document.getElementById('acUsername').value = u.username;
                document.getElementById('acRole').value = u.role;
                document.getElementById('acPhone').value = u.phone || '';
                document.getElementById('accountModalTitle').textContent = '编辑账号';
            }
        }
        App.openModal('accountModal');
    }

    async function saveAccount() {
        const id = document.getElementById('acId').value;
        const body = {
            username: document.getElementById('acUsername').value.trim(),
            role: document.getElementById('acRole').value,
            phone: document.getElementById('acPhone').value.trim()
        };
        if (!body.username) {
            App.toast('请填写用户名', 'warning');
            return;
        }
        try {
            let r;
            if (id) {
                body.id = Number(id);
                r = await App.post('/api/admin/user/update', body);
            } else {
                const pwd = document.getElementById('acPassword').value.trim();
                if (pwd) {
                    body.password = pwd;
                }
                r = await App.post('/api/admin/user/add', body);
            }
            App.toast(r.message, 'success');
            App.closeModal('accountModal');
            await loadAccounts();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function toggleAccount(id, status) {
        try {
            const r = await App.post('/api/admin/user/' + id + '/status?status=' + status);
            App.toast(r.message, 'success');
            await loadAccounts();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function resetPassword(id) {
        const u = accounts.find(x => x.id === id);
        const pwd = prompt('请输入「' + (u ? u.username : id) + '」的新密码：', '123456');
        if (pwd === null) {
            return;
        }
        try {
            const r = await App.post('/api/admin/user/' + id + '/reset-password', { password: pwd });
            App.toast('密码已重置为：' + r.data.password, 'success');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function deleteAccount(id) {
        const u = accounts.find(x => x.id === id);
        if (!confirm('确定要删除账号「' + (u ? u.username : id) + '」吗？此操作不可恢复。')) {
            return;
        }
        try {
            const r = await App.del('/api/admin/user/delete/' + id);
            App.toast(r.message, 'success');
            await loadAccounts();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 7. 地块总览
    // ==================================================================
    async function loadPlots() {
        try {
            const r = await App.get('/api/farmland/all');
            plots = r.data || [];
            const tbody = document.getElementById('plotTableBody');
            if (plots.length === 0) {
                tbody.innerHTML = App.emptyRow(10, '暂无地块');
                return;
            }
            tbody.innerHTML = plots.map(function (p) {
                return '<tr>'
                    + '<td>' + p.id + '</td>'
                    + '<td><b>' + App.esc(p.name) + '</b></td>'
                    + '<td>' + App.orDash(p.username) + '</td>'
                    + '<td>' + App.orDash(p.location) + '</td>'
                    + '<td>' + App.num(p.area) + '</td>'
                    + '<td>' + App.orDash(p.cropType) + '</td>'
                    + '<td>' + App.badge(p.autoIrrigation === 1 ? '自动' : '手动',
                        p.autoIrrigation === 1 ? 'purple' : 'gray') + '</td>'
                    + '<td>' + (p.deviceCount || 0) + '</td>'
                    + '<td>' + (p.onlineDeviceCount || 0) + '</td>'
                    + '<td>' + ((p.pendingAlarmCount || 0) > 0
                        ? '<b style="color:#b91c1c">' + p.pendingAlarmCount + '</b>' : '0') + '</td>'
                    + '</tr>';
            }).join('');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 8. 水肥配比
    // ==================================================================
    async function loadFertilizers() {
        try {
            const r = await App.get('/api/fertilizer/list');
            fertilizers = r.data || [];
            renderFertilizerTable(fertilizers);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderFertilizerTable(list) {
        const tbody = document.getElementById('fertilizerTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(9, '暂无配比方案');
            return;
        }
        tbody.innerHTML = list.map(function (x) {
            return '<tr>'
                + '<td><b>' + App.esc(x.name) + '</b></td>'
                + '<td>' + App.orDash(x.cropType) + '</td>'
                + '<td>' + (x.farmlandName ? App.esc(x.farmlandName) : '通用') + '</td>'
                + '<td><b>' + App.num(x.nRatio) + ' : ' + App.num(x.pRatio) + ' : ' + App.num(x.kRatio) + '</b></td>'
                + '<td>' + App.num(x.ecTarget, 2) + '</td>'
                + '<td>' + App.num(x.phTarget, 2) + '</td>'
                + '<td>' + App.num(x.concentration, 2) + '%</td>'
                + '<td>' + App.badge(x.enabled === 1 ? '启用' : '停用',
                    x.enabled === 1 ? 'success' : 'gray') + '</td>'
                + '<td><div class="btn-row">'
                + '<button class="btn btn-outline btn-sm" onclick="Admin.openFertilizerModal(' + x.id + ')">编辑</button>'
                + '<button class="btn btn-danger-outline btn-sm" onclick="Admin.deleteFertilizer(' + x.id + ')">删除</button>'
                + '</div></td>'
                + '</tr>';
        }).join('');
    }

    function openFertilizerModal(id) {
        document.getElementById('fId').value = '';
        document.getElementById('fName').value = '';
        document.getElementById('fCrop').value = '';
        document.getElementById('fFarmland').value = '';
        document.getElementById('fN').value = '3';
        document.getElementById('fP').value = '1';
        document.getElementById('fK').value = '2';
        document.getElementById('fEc').value = '1.8';
        document.getElementById('fPh').value = '6.2';
        document.getElementById('fConc').value = '0.2';
        document.getElementById('fEnabled').value = '1';
        document.getElementById('fertilizerModalTitle').textContent = '新增水肥配比方案';

        if (id) {
            const x = fertilizers.find(v => v.id === id);
            if (x) {
                document.getElementById('fId').value = x.id;
                document.getElementById('fName').value = x.name;
                document.getElementById('fCrop').value = x.cropType || '';
                document.getElementById('fFarmland').value = x.farmlandId || '';
                document.getElementById('fN').value = x.nRatio;
                document.getElementById('fP').value = x.pRatio;
                document.getElementById('fK').value = x.kRatio;
                document.getElementById('fEc').value = x.ecTarget;
                document.getElementById('fPh').value = x.phTarget;
                document.getElementById('fConc').value = x.concentration;
                document.getElementById('fEnabled').value = String(x.enabled);
                document.getElementById('fertilizerModalTitle').textContent = '编辑水肥配比方案';
            }
        }
        if (document.getElementById('fFarmland').options.length <= 1) {
            fillSelect('fFarmland', plots.map(p => ({ value: p.id, label: p.name })),
                '通用方案（所有地块可用）');
        }
        App.openModal('fertilizerModal');
    }

    async function saveFertilizer() {
        const body = {
            name: document.getElementById('fName').value.trim(),
            cropType: document.getElementById('fCrop').value.trim(),
            farmlandId: document.getElementById('fFarmland').value
                ? Number(document.getElementById('fFarmland').value) : null,
            nRatio: Number(document.getElementById('fN').value),
            pRatio: Number(document.getElementById('fP').value),
            kRatio: Number(document.getElementById('fK').value),
            ecTarget: Number(document.getElementById('fEc').value),
            phTarget: Number(document.getElementById('fPh').value),
            concentration: Number(document.getElementById('fConc').value),
            enabled: Number(document.getElementById('fEnabled').value)
        };
        const id = document.getElementById('fId').value;
        if (id) {
            body.id = Number(id);
        }
        try {
            const r = await App.post('/api/fertilizer/save', body);
            App.toast(r.message, 'success');
            App.closeModal('fertilizerModal');
            await loadFertilizers();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function deleteFertilizer(id) {
        const x = fertilizers.find(v => v.id === id);
        if (!confirm('确定要删除方案「' + (x ? x.name : id) + '」吗？')) {
            return;
        }
        try {
            const r = await App.del('/api/fertilizer/delete/' + id);
            App.toast(r.message, 'success');
            await loadFertilizers();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 9. 报表导出与数据维护
    // ==================================================================
    function exportReport() {
        const type = document.getElementById('rType').value;
        const days = document.getElementById('rDays').value;
        App.toast('正在生成报表，请稍候...', 'info');
        // 后端返回 Content-Disposition: attachment，浏览器会直接下载
        window.location.href = '/api/report/export?type=' + type + '&days=' + days;
    }

    async function cleanup() {
        const keepDays = Number(document.getElementById('keepDays').value);
        if (!keepDays || keepDays < 7) {
            App.toast('保留天数不能少于 7 天', 'warning');
            return;
        }
        if (!confirm('确定要清理 ' + keepDays + ' 天前的历史监测数据吗？此操作不可恢复。')) {
            return;
        }
        try {
            const r = await App.post('/api/stats/cleanup?keepDays=' + keepDays);
            App.toast(r.message, 'success');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function scanNow() {
        try {
            const r = await App.post('/api/irrigation/auto/scan');
            App.toast(r.message, 'success');
            await loadOverview();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function loadOverviewIfActive() {
        if (document.getElementById('view-overview').classList.contains('active')) {
            await loadOverview();
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        init().catch(function (e) {
            App.toast(e.message || '页面初始化失败', 'error');
        });
        // 定时刷新全局看板
        setInterval(function () {
            if (document.getElementById('view-overview').classList.contains('active')) {
                loadOverview();
            }
        }, 60000);
    });

    return {
        loadOverview, loadDevices, loadStrategies, loadRules, loadAlarms,
        loadAccounts, loadPlots, loadFertilizers, loadDeviceOptions,
        toggleAll, syncSelection, syncAlarmSelection,
        openDeviceModal, saveDevice, deleteDevice, heartbeat, bindDevice, showBindPrompt,
        batchBind, batchUnbind, batchStatus,
        openStrategyModal, saveStrategy, toggleStrategy, deleteStrategy, applyStrategy,
        onScopeChange, onMetricChange, onLevelChange,
        openRuleModal, saveRule, deleteRule,
        handleAlarm, batchHandleAlarm,
        openAccountModal, saveAccount, toggleAccount, resetPassword, deleteAccount,
        openFertilizerModal, saveFertilizer, deleteFertilizer,
        exportReport, cleanup, scanNow
    };
})();
