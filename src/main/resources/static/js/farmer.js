/* ============================================================
   农户操作端逻辑
   操作流程：查看实时监测看板 → 选择灌溉模式（手动/自动） → 接收并处理告警
   ============================================================ */
const Farmer = (function () {

    let me = null;
    let plots = [];
    let currentPlotId = null;
    let recipes = [];

    const VIEW_TITLES = {
        dashboard: '实时监测看板',
        irrigation: '灌溉管理',
        alarms: '告警消息',
        devices: '我的设备',
        stats: '历史统计',
        fertilizer: '水肥配比',
        profile: '个人设置'
    };

    // ==================================================================
    // 初始化
    // ==================================================================
    async function init() {
        me = await App.guard('farmer');
        if (!me) {
            return;
        }

        document.getElementById('sideUserName').textContent = me.username;
        document.getElementById('topUserName').textContent = me.username;
        document.getElementById('phone').value = me.phone || '';

        bindNav();
        bindBell();
        startClock();

        await loadPlots();
        await loadDurationOptions();
        await loadRecords();
        await loadAlarms();
        await loadDevices();
        await loadFertilizer();
        loadStats();

        // 启动告警推送中心：只有高等级告警会进入推送队列
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

                // 切换到对应页面时刷新数据
                if (view === 'alarms') loadAlarms();
                if (view === 'devices') loadDevices();
                if (view === 'stats') loadStats();
                if (view === 'fertilizer') loadFertilizer();
                if (view === 'irrigation') refreshIrrigationView();
            });
        });
    }

    function bindBell() {
        document.getElementById('bellBtn').addEventListener('click', function () {
            document.querySelector('.nav-item[data-view="alarms"]').click();
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
        // 有高等级未处理时铃铛显示为红色闪烁提示
        dot.style.background = high > 0 ? '#ef4444' : '#f59e0b';
    }

    // ==================================================================
    // 地块
    // ==================================================================
    async function loadPlots() {
        const r = await App.get('/api/stats/dashboard');
        const data = r.data;
        plots = data.farmlands || [];

        renderPlotCards(document.getElementById('plotList'), plots, function (id) {
            selectPlot(id);
        });
        renderPlotCards(document.getElementById('irrigationPlotList'), plots, function (id) {
            selectPlot(id);
            refreshIrrigationView();
        });
        renderStatsPlotOptions();

        if (plots.length === 0) {
            currentPlotId = null;
            renderSensorTiles(null);
            return;
        }
        // 默认选中第一个地块
        if (!currentPlotId) {
            selectPlot(plots[0].id);
        } else {
            selectPlot(currentPlotId);
        }
    }

    function renderPlotCards(container, list, onClick) {
        if (!container) {
            return;
        }
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="muted small">暂无地块，请联系管理员分配</div>';
            return;
        }
        container.innerHTML = list.map(function (p) {
            const hs = App.humidityStatus(p.soilHumidity, p.temperature);
            return '<div class="plot-card ' + (p.id === currentPlotId ? 'selected' : '') + '" data-id="' + p.id + '">'
                + '<div class="plot-name"><i class="fas fa-map-location-dot me-1"></i>' + App.esc(p.name) + '</div>'
                + '<div class="plot-meta">' + App.orDash(p.location) + ' · ' + App.orDash(p.cropType)
                + ' · ' + App.num(p.area) + ' 亩</div>'
                + '<div class="mb-1">'
                + App.badge(p.autoIrrigation === 1 ? '自动模式' : '手动模式',
                    p.autoIrrigation === 1 ? 'purple' : 'gray')
                + ' ' + App.badge(hs.text, hs.cls)
                + '</div>'
                + '<div class="plot-readings">'
                + '<div>湿度<b>' + App.num(p.soilHumidity) + '%</b></div>'
                + '<div>温度<b>' + App.num(p.temperature) + '℃</b></div>'
                + '<div>设备<b>' + (p.onlineDeviceCount || 0) + '/' + (p.deviceCount || 0) + '</b></div>'
                + '</div></div>';
        }).join('');

        container.querySelectorAll('.plot-card').forEach(function (card) {
            card.addEventListener('click', function () {
                onClick(Number(this.dataset.id));
            });
        });
    }

    function currentPlot() {
        return plots.find(p => p.id === currentPlotId) || null;
    }

    function selectPlot(id) {
        currentPlotId = id;
        const plot = currentPlot();

        // 同步所有地块卡片的高亮状态
        document.querySelectorAll('.plot-card').forEach(function (card) {
            card.classList.toggle('selected', Number(card.dataset.id) === id);
        });

        const label = plot ? ' - ' + App.esc(plot.name) : '';
        document.getElementById('currentPlotName').innerHTML = label;

        renderPlotStatus(plot);
        loadSensorData();
        loadRecords();
        refreshIrrigationView();
        updateManualNotice();
    }

    /** 右侧"地块状态"卡片：当前模式、设备概况与待处理告警 */
    function renderPlotStatus(plot) {
        const box = document.getElementById('plotStatusBox');
        if (!plot) {
            box.innerHTML = '<div class="muted small">请先选择地块</div>';
            return;
        }
        const hs = App.humidityStatus(plot.soilHumidity, plot.temperature);
        const offline = (plot.deviceCount || 0) - (plot.onlineDeviceCount || 0);

        box.innerHTML = '<table class="data-table" style="font-size:13px"><tbody>'
            + '<tr><td style="color:#6b7280">地块名称</td><td><b>' + App.esc(plot.name) + '</b></td></tr>'
            + '<tr><td style="color:#6b7280">位置</td><td>' + App.orDash(plot.location) + '</td></tr>'
            + '<tr><td style="color:#6b7280">作物 / 面积</td><td>' + App.orDash(plot.cropType)
            + ' / ' + App.num(plot.area) + ' 亩</td></tr>'
            + '<tr><td style="color:#6b7280">灌溉模式</td><td>'
            + App.badge(plot.autoIrrigation === 1 ? '自动模式' : '手动模式',
                plot.autoIrrigation === 1 ? 'purple' : 'gray') + '</td></tr>'
            + '<tr><td style="color:#6b7280">生效策略</td><td>'
            + App.orDash(plot.strategyName || '全局策略') + '</td></tr>'
            + '<tr><td style="color:#6b7280">当前湿度</td><td>' + App.num(plot.soilHumidity) + '% '
            + App.badge(hs.text, hs.cls) + '</td></tr>'
            + '<tr><td style="color:#6b7280">设备在线</td><td>' + (plot.onlineDeviceCount || 0)
            + ' / ' + (plot.deviceCount || 0)
            + (offline > 0 ? ' <span style="color:#b45309">（' + offline + ' 台异常）</span>' : '') + '</td></tr>'
            + '<tr><td style="color:#6b7280">待处理告警</td><td>' + ((plot.pendingAlarmCount || 0) > 0
                ? '<b style="color:#b91c1c">' + plot.pendingAlarmCount + ' 条</b>' : '0 条') + '</td></tr>'
            + '</tbody></table>';
    }

    async function reloadPlots() {
        await loadPlots();
        App.toast('地块数据已刷新', 'success');
    }

    // ==================================================================
    // 实时监测
    // ==================================================================
    async function loadSensorData() {
        if (!currentPlotId) {
            renderSensorTiles(null);
            return;
        }
        try {
            const [realtime, trend] = await Promise.all([
                App.get('/api/sensor/realtime/' + currentPlotId),
                App.get('/api/sensor/trend/' + currentPlotId + '?hours=24')
            ]);
            renderSensorTiles(realtime.data);
            renderTrendChart(trend.data.list || []);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderSensorTiles(data) {
        const box = document.getElementById('sensorTiles');
        const freshness = document.getElementById('dataFreshness');
        const notice = document.getElementById('sensorNotice');
        notice.style.display = 'none';

        if (!data || !data.hasData || !data.latest) {
            box.innerHTML = '<div class="muted small">该地块暂无监测数据。</div>'
                + '<div class="muted small">可点击"模拟采集"生成一条演示数据。</div>';
            freshness.textContent = '暂无数据';
            return;
        }

        const d = data.latest;
        const hs = App.humidityStatus(d.soilHumidity, d.temperature);
        const ts = App.temperatureStatus(d.temperature);
        const ls = App.lightStatus(d.lightIntensity);

        box.innerHTML = ''
            + tile('土壤湿度', d.soilHumidity, '%', hs, 1)
            + tile('温度', d.temperature, '℃', ts, 1)
            + tile('光照强度', d.lightIntensity, 'lux', ls, 0);

        const age = data.dataAgeMinutes;
        freshness.innerHTML = '采集时间：' + App.fmtDateTime(d.collectTime)
            + '（' + App.timeAgo(d.collectTime) + '）'
            + (data.dataValid
                ? ' <span class="badge-soft bg-soft-success">数据有效</span>'
                : ' <span class="badge-soft bg-soft-warning">数据已过期</span>');

        if (!data.dataValid) {
            notice.className = 'notice notice-warn mt-3';
            notice.style.display = 'flex';
            notice.innerHTML = '<i class="fas fa-triangle-exclamation"></i><div>监测数据已超过 '
                + age + ' 分钟未更新。自动灌溉不会基于过期数据执行，请检查采集设备是否在线。</div>';
        } else if (hs.cls === 'danger') {
            notice.className = 'notice notice-danger mt-3';
            notice.style.display = 'flex';
            notice.innerHTML = '<i class="fas fa-circle-exclamation"></i><div>当前土壤湿度严重偏低，建议立即灌溉。'
                + '系统已按阈值规则生成高等级告警。</div>';
        }
    }

    function tile(name, value, unit, status, digits) {
        return '<div class="sensor-tile">'
            + '<div class="name">' + name + '</div>'
            + '<div class="reading">' + App.num(value, digits) + '<span class="unit">' + unit + '</span></div>'
            + '<div>' + App.badge(status.text, status.cls) + '</div>'
            + '</div>';
    }

    function renderTrendChart(list) {
        const labels = list.map(s => App.fmtShort(s.collectTime));
        App.lineChart('trendChart', labels, [
            {
                label: '土壤湿度 (%)',
                data: list.map(s => s.soilHumidity),
                borderColor: '#3b82f6',
                backgroundColor: 'rgba(59,130,246,.12)',
                borderWidth: 2.5,
                fill: true,
                tension: .35,
                pointRadius: list.length > 30 ? 0 : 2
            },
            {
                label: '温度 (℃)',
                data: list.map(s => s.temperature),
                borderColor: '#f59e0b',
                backgroundColor: 'rgba(245,158,11,.08)',
                borderWidth: 2,
                fill: false,
                tension: .35,
                pointRadius: 0,
                yAxisID: 'y1'
            }
        ], {
            scales: {
                y: {
                    position: 'left',
                    title: { display: true, text: '湿度 (%)' },
                    grid: { color: 'rgba(0,0,0,.05)' }
                },
                y1: {
                    position: 'right',
                    title: { display: true, text: '温度 (℃)' },
                    grid: { display: false }
                },
                x: { grid: { display: false }, ticks: { maxTicksLimit: 8 } }
            }
        });
        document.getElementById('trendWrap').style.height = '280px';
    }

    async function refreshSensor() {
        if (!currentPlotId) {
            App.toast('请先选择地块', 'warning');
            return;
        }
        await loadSensorData();
        App.toast('监测数据已刷新', 'success');
    }

    async function mockData() {
        if (!currentPlotId) {
            App.toast('请先选择地块', 'warning');
            return;
        }
        try {
            await App.post('/api/irrigation/mock/' + currentPlotId);
            App.toast('已生成一条模拟采集数据', 'success');
            await loadSensorData();
            await loadPlots();
            await loadAlarms();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 灌溉管理
    // ==================================================================
    async function loadDurationOptions() {
        try {
            const r = await App.get('/api/irrigation/options');
            document.getElementById('mDuration').innerHTML = (r.data.durations || [])
                .map(d => '<option value="' + d + '"' + (d === 10 ? ' selected' : '') + '>' + d + ' 分钟</option>')
                .join('');
        } catch (e) {
            /* 使用默认选项 */
        }
    }

    async function loadFertilizer() {
        try {
            // 加载当前地块可用的水肥方案（专属 + 通用）
            const url = currentPlotId
                ? '/api/fertilizer/list?farmlandId=' + currentPlotId + '&onlyEnabled=true'
                : '/api/fertilizer/list?onlyEnabled=true';
            const r = await App.get(url);
            recipes = r.data || [];

            const select = document.getElementById('mRecipe');
            select.innerHTML = '<option value="">不施肥，仅灌溉清水</option>'
                + recipes.map(x => '<option value="' + x.id + '">' + App.esc(x.name)
                    + '（N:P:K = ' + App.num(x.nRatio) + ':' + App.num(x.pRatio) + ':' + App.num(x.kRatio) + '）</option>')
                .join('');

            document.getElementById('fertilizerSummary').textContent =
                '共 ' + recipes.length + ' 个可用方案（含通用方案）';
            document.getElementById('fertilizerTableBody').innerHTML = recipes.length === 0
                ? App.emptyRow(8, '暂无可用水肥配比方案，请联系管理员配置')
                : recipes.map(function (x) {
                    return '<tr>'
                        + '<td>' + App.esc(x.name) + '</td>'
                        + '<td>' + App.orDash(x.cropType) + '</td>'
                        + '<td>' + App.orDash(x.farmlandName || '通用') + '</td>'
                        + '<td><b>' + App.num(x.nRatio) + ' : ' + App.num(x.pRatio) + ' : ' + App.num(x.kRatio) + '</b></td>'
                        + '<td>' + App.num(x.ecTarget, 2) + '</td>'
                        + '<td>' + App.num(x.phTarget, 2) + '</td>'
                        + '<td>' + App.num(x.concentration, 2) + '</td>'
                        + '<td>' + App.badge(x.enabled === 1 ? '启用' : '停用',
                            x.enabled === 1 ? 'success' : 'gray') + '</td>'
                        + '</tr>';
                }).join('');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function updateManualNotice() {
        const box = document.getElementById('manualNotice');
        const plot = currentPlot();
        if (!plot) {
            box.className = 'notice notice-warn';
            box.innerHTML = '<i class="fas fa-triangle-exclamation"></i><div>请先在上方选择要灌溉的地块。</div>';
            return;
        }
        box.className = 'notice notice-info';
        box.innerHTML = '<i class="fas fa-circle-info"></i><div>当前地块：<b>' + App.esc(plot.name)
            + '</b>，当前灌溉模式为 <b>' + (plot.autoIrrigation === 1 ? '自动模式' : '手动模式')
            + '</b>。手动指令会立即开始执行，同一地块同时只能有一个灌溉任务。</div>';
    }

    async function refreshIrrigationView() {
        updateManualNotice();
        await loadAutoStatus();
        await loadRecipesForPlot();
    }

    async function loadRecipesForPlot() {
        if (currentPlotId) {
            await loadFertilizer();
        }
    }

    async function loadAutoStatus() {
        const box = document.getElementById('autoStatusBox');
        const sw = document.getElementById('autoSwitch');
        const swText = document.getElementById('autoSwitchText');

        if (!currentPlotId) {
            box.textContent = '请先选择地块';
            sw.checked = false;
            swText.textContent = '手动模式';
            return;
        }

        try {
            const r = await App.get('/api/irrigation/auto/status/' + currentPlotId);
            const d = r.data;

            sw.checked = d.autoIrrigation === 1;
            swText.textContent = d.mode;

            const s = d.strategy;
            let html = '';

            if (d.autoIrrigation !== 1) {
                html += '<div class="notice notice-warn" style="margin-bottom:12px">'
                    + '<i class="fas fa-hand-pointer"></i><div>当前为手动模式，系统不会自动执行灌溉。'
                    + '开启后需同时满足"湿度低于下限 + 处于允许时段 + 设备在线 + 未超冷却期"才会自动灌溉。</div></div>';
            } else if (d.hint) {
                const cls = d.belowThreshold ? 'notice-success' : 'notice-info';
                html += '<div class="notice ' + cls + '" style="margin-bottom:12px">'
                    + '<i class="fas fa-robot"></i><div>' + App.esc(d.hint) + '</div></div>';
            }

            if (s) {
                html += '<table class="data-table" style="font-size:13px">'
                    + row('生效策略', App.esc(s.name) + (s.isGlobal === 1 ? '（全局）' : '（地块专属）'))
                    + row('触发湿度下限', App.num(s.soilHumidityMin) + ' %')
                    + row('目标湿度', App.num(s.targetHumidity) + ' %')
                    + row('温度上限', App.num(s.temperatureMax) + ' ℃')
                    + row('光照上限', App.num(s.lightIntensityMax, 0) + ' lux')
                    + row('单次灌溉量', App.num(s.irrigationAmount) + ' m³ / ' + s.durationMinutes + ' 分钟')
                    + row('允许灌溉时段', App.esc(s.allowedStartTime) + ' ~ ' + App.esc(s.allowedEndTime))
                    + '</table>';
            } else {
                html += '<div class="notice notice-warn">该地块尚未绑定策略且系统无全局策略，请联系管理员配置。</div>';
            }

            if (d.currentHumidity !== null && d.currentHumidity !== undefined) {
                html += '<div class="muted small mt-2">当前土壤湿度：<b>' + App.num(d.currentHumidity) + '%</b>'
                    + (d.humidityMin ? '（下限 ' + App.num(d.humidityMin) + '%）' : '')
                    + (d.dataValid ? '' : ' · <span style="color:#b45309">数据已过期</span>')
                    + '</div>';
            }
            box.innerHTML = html;
        } catch (e) {
            box.innerHTML = '<div class="muted small">' + App.esc(e.message) + '</div>';
        }
    }

    function row(label, value) {
        return '<tr><td style="width:45%;color:#6b7280">' + label + '</td><td><b>' + value + '</b></td></tr>';
    }

    async function toggleAuto(checkbox) {
        if (!currentPlotId) {
            checkbox.checked = false;
            App.toast('请先选择地块', 'warning');
            return;
        }
        const enabled = checkbox.checked;
        try {
            const r = await App.post('/api/farmland/' + currentPlotId + '/auto?enabled=' + enabled);
            App.toast(r.message, 'success');
            await loadPlots();
            await loadAutoStatus();
        } catch (e) {
            checkbox.checked = !enabled;
            App.toast(e.message, 'error');
        }
    }

    async function checkDecision() {
        if (!currentPlotId) {
            App.toast('请先选择地块', 'warning');
            return;
        }
        const box = document.getElementById('decisionBox');
        try {
            const r = await App.post('/api/irrigation/decision/' + currentPlotId);
            const d = r.data;
            const need = d.needIrrigation;

            box.innerHTML = '<div class="notice ' + (need ? 'notice-warn' : 'notice-success') + ' mt-3">'
                + '<i class="fas ' + (need ? 'fa-droplet' : 'fa-circle-check') + '"></i><div>'
                + '<b>决策结果：' + App.esc(d.decisionResult) + '</b>（置信度 ' + App.num(d.confidence) + '%）<br>'
                + App.esc(d.reason) + '<br>'
                + (need ? '<b>建议灌溉量：' + App.num(d.recommendedWater) + ' m³</b>' : '')
                + '<div class="muted small mt-1">依据策略：' + App.esc(d.strategyName) + '</div>'
                + '</div></div>';

            if (need && d.recommendedWater) {
                document.getElementById('mWater').value = d.recommendedWater;
            }
        } catch (e) {
            box.innerHTML = '<div class="notice notice-danger mt-3"><i class="fas fa-circle-exclamation"></i><div>'
                + App.esc(e.message) + '</div></div>';
        }
    }

    async function manualIrrigate() {
        if (!currentPlotId) {
            App.toast('请先选择地块', 'warning');
            return;
        }
        const water = parseFloat(document.getElementById('mWater').value);
        if (!water || water <= 0) {
            App.toast('请填写有效的灌溉水量', 'warning');
            return;
        }
        const recipeVal = document.getElementById('mRecipe').value;

        const btn = document.getElementById('manualBtn');
        btn.disabled = true;
        try {
            const r = await App.post('/api/irrigation/manual', {
                farmlandId: currentPlotId,
                waterAmount: water,
                durationMinutes: Number(document.getElementById('mDuration').value),
                fertilizerRecipeId: recipeVal ? Number(recipeVal) : null,
                remark: document.getElementById('mRemark').value
            });
            App.toast(r.message, 'success');
            document.getElementById('mRemark').value = '';
            await loadRecords();
            await loadPlots();
        } catch (e) {
            App.toast(e.message, 'error');
        } finally {
            btn.disabled = false;
        }
    }

    async function loadRecords() {
        if (!currentPlotId) {
            document.getElementById('recordTableBody').innerHTML = App.emptyRow(9, '请先选择地块');
            document.getElementById('recentRecords').innerHTML = '<div class="muted small">请先选择地块</div>';
            return;
        }
        try {
            const r = await App.get('/api/irrigation/records/farmland/' + currentPlotId + '?limit=50');
            const list = r.data || [];
            renderRecordTable(list);
            renderRecentRecords(list.slice(0, 5));
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderRecordTable(list) {
        const tbody = document.getElementById('recordTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(9, '该地块暂无灌溉记录');
            return;
        }
        tbody.innerHTML = list.map(function (x) {
            const canCancel = x.status === 'RUNNING' || x.status === 'PENDING';
            return '<tr>'
                + '<td>' + App.fmtDateTime(x.createdAt) + '</td>'
                + '<td>' + App.orDash(x.farmlandName) + '</td>'
                + '<td>' + App.badge(x.triggerType === 'AUTO' ? '自动' : '手动',
                    x.triggerType === 'AUTO' ? 'purple' : 'info') + '</td>'
                + '<td>' + App.num(x.waterAmount) + '</td>'
                + '<td>' + App.orDash(x.durationMinutes) + '</td>'
                + '<td>' + App.orDash(x.fertilizerName || '清水') + '</td>'
                + '<td>' + App.badge(App.IRRIGATION_STATUS_TEXT[x.status] || x.status,
                    App.IRRIGATION_STATUS_CLASS[x.status] || 'gray') + '</td>'
                + '<td class="small muted">' + App.orDash(x.remark) + '</td>'
                + '<td>' + (canCancel
                    ? '<button class="btn btn-danger-outline btn-sm" onclick="Farmer.cancelRecord(' + x.id + ')">取消</button>'
                    : '<span class="muted small">--</span>') + '</td>'
                + '</tr>';
        }).join('');
    }

    function renderRecentRecords(list) {
        const box = document.getElementById('recentRecords');
        if (list.length === 0) {
            box.innerHTML = '<div class="muted small">暂无灌溉记录</div>';
            return;
        }
        box.innerHTML = list.map(function (x) {
            return '<div style="padding:8px 0;border-bottom:1px solid #f1f3f5">'
                + '<div class="d-flex justify-content-between align-items-center">'
                + '<span>' + App.badge(x.triggerType === 'AUTO' ? '自动' : '手动',
                    x.triggerType === 'AUTO' ? 'purple' : 'info')
                + ' <b>' + App.num(x.waterAmount) + ' m³</b></span>'
                + App.badge(App.IRRIGATION_STATUS_TEXT[x.status] || x.status,
                    App.IRRIGATION_STATUS_CLASS[x.status] || 'gray')
                + '</div>'
                + '<div class="muted" style="font-size:12px;margin-top:3px">'
                + App.fmtDateTime(x.createdAt) + '</div>'
                + '</div>';
        }).join('');
    }

    async function cancelRecord(id) {
        if (!confirm('确定要取消这条灌溉任务吗？')) {
            return;
        }
        try {
            const r = await App.post('/api/irrigation/cancel/' + id);
            App.toast(r.message, 'success');
            await loadRecords();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 告警
    // ==================================================================
    async function loadAlarms() {
        try {
            const level = document.getElementById('alarmLevelFilter').value;
            const status = document.getElementById('alarmStatusFilter').value;
            const params = [];
            if (level) params.push('level=' + level);
            if (status) params.push('status=' + status);
            params.push('limit=100');

            const r = await App.get('/api/alarm/my?' + params.join('&'));
            const list = r.data || [];
            renderAlarms(list);
            loadAlarmSummary();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function loadAlarmSummary() {
        try {
            const r = await App.get('/api/alarm/unread');
            const d = r.data;
            document.getElementById('alarmSummary').innerHTML =
                '待处理共 <b>' + d.unread + '</b> 条，其中高等级 <b style="color:#b91c1c">' + d.unreadHigh
                + '</b> 条；历史未解决 <b>' + d.unresolved + '</b> 条';
            updateAlarmBadge(d);
        } catch (e) {
            /* 忽略 */
        }
    }

    function renderAlarms(list) {
        const box = document.getElementById('alarmList');
        if (list.length === 0) {
            box.innerHTML = '<div class="muted small text-center" style="padding:30px 0">'
                + '<i class="fas fa-circle-check" style="font-size:26px;display:block;margin-bottom:8px;opacity:.5"></i>'
                + '暂无告警记录</div>';
            return;
        }

        box.innerHTML = list.map(function (a) {
            const isHigh = a.alarmLevel === 'HIGH';
            const levelText = App.LEVEL_TEXT[a.alarmLevel] || a.alarmLevel;
            const levelCls = App.LEVEL_CLASS[a.alarmLevel] || 'gray';
            const pushTag = a.pushStatus === 1
                ? App.badge('已推送通知', 'danger')
                : App.badge('仅记录', 'gray');

            return '<div class="alarm-item level-' + a.alarmLevel + '">'
                + '<div class="alarm-head">'
                + App.badge(levelText, levelCls)
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
                + (a.deviceName ? '<span><i class="fas fa-microchip me-1"></i>' + App.esc(a.deviceName)
                    + ' (' + App.esc(a.deviceCode) + ')</span>' : '')
                + (a.handledByName ? '<span><i class="fas fa-user-check me-1"></i>' + App.esc(a.handledByName)
                    + ' 已处理</span>' : '')
                + '<span class="ms-auto">'
                + (a.status !== 'RESOLVED'
                    ? '<button class="btn btn-outline btn-sm" onclick="Farmer.handleAlarm(' + a.id + ', \'PROCESSING\')">处理中</button> '
                    + '<button class="btn btn-primary btn-sm" onclick="Farmer.handleAlarm(' + a.id + ', \'RESOLVED\')">标记已解决</button>'
                    : '<span class="muted small">'
                    + App.orDash(a.handleRemark) + '</span>')
                + '</span>'
                + '</div></div>';
        }).join('');
    }

    async function handleAlarm(id, status) {
        let remark = '';
        if (status === 'RESOLVED') {
            remark = prompt('请填写处理说明（可选）：', '已排查处理') || '';
        }
        try {
            const r = await App.post('/api/alarm/' + id + '/handle', { status: status, remark: remark });
            App.toast(r.message, 'success');
            await loadAlarms();
            await loadPlots();
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 设备
    // ==================================================================
    async function loadDevices() {
        try {
            const r = await App.get('/api/device/my');
            const list = r.data || [];
            const online = list.filter(d => d.status === 'ONLINE').length;
            const abnormal = list.filter(d => d.status === 'OFFLINE' || d.status === 'FAULT').length;

            document.getElementById('deviceSummary').innerHTML =
                '共 <b>' + list.length + '</b> 台设备，在线 <b>' + online + '</b> 台'
                + (abnormal > 0 ? '，异常 <b style="color:#b91c1c">' + abnormal + '</b> 台' : '');

            const box = document.getElementById('deviceList');
            if (list.length === 0) {
                box.innerHTML = '<div class="muted small">暂无设备</div>';
                return;
            }

            box.innerHTML = list.map(function (d) {
                const stale = d.offlineMinutes !== null && d.offlineMinutes !== undefined
                    && d.offlineMinutes > 30 && d.status === 'ONLINE';
                return '<div class="plot-card" style="cursor:default">'
                    + '<div class="flex-between mb-1">'
                    + '<span class="plot-name">' + App.esc(d.deviceName) + '</span>'
                    + App.badge(App.DEVICE_STATUS_TEXT[d.status] || d.status,
                        App.DEVICE_STATUS_CLASS[d.status] || 'gray')
                    + '</div>'
                    + '<div class="plot-meta">' + App.esc(d.deviceCode)
                    + ' · ' + (App.DEVICE_TYPE_TEXT[d.deviceType] || d.deviceType) + '</div>'
                    + '<div class="plot-meta"><i class="fas fa-location-dot me-1"></i>'
                    + App.orDash(d.installLocation)
                    + (d.farmlandName ? ' · ' + App.esc(d.farmlandName) : '') + '</div>'
                    + '<div class="muted" style="font-size:12px">'
                    + '<i class="fas fa-signal me-1"></i>最后心跳：'
                    + (d.lastOnlineTime ? App.fmtDateTime(d.lastOnlineTime) + '（' + App.timeAgo(d.lastOnlineTime) + '）' : '无记录')
                    + '</div>'
                    + (d.status === 'FAULT'
                        ? '<div class="notice notice-danger mt-2 mb-0"><i class="fas fa-triangle-exclamation"></i>'
                        + '<div>设备故障：请检查供电、接线与探头状态，处理后联系管理员恢复。</div></div>'
                        : '')
                    + (d.status === 'OFFLINE'
                        ? '<div class="notice notice-warn mt-2 mb-0"><i class="fas fa-plug-circle-xmark"></i>'
                        + '<div>设备离线：请检查电源与网络连接，设备恢复上报后会转为在线。</div></div>'
                        : '')
                    + (stale
                        ? '<div class="notice notice-warn mt-2 mb-0"><i class="fas fa-clock"></i>'
                        + '<div>心跳已超过 ' + d.offlineMinutes + ' 分钟未更新，可能即将被判为离线。</div></div>'
                        : '')
                    + '</div>';
            }).join('');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // ==================================================================
    // 历史统计
    // ==================================================================
    function renderStatsPlotOptions() {
        const sel = document.getElementById('statsPlot');
        sel.innerHTML = '<option value="">全部地块（汇总）</option>'
            + plots.map(p => '<option value="' + p.id + '">' + App.esc(p.name) + '</option>').join('');
    }

    async function loadStats() {
        const plotId = document.getElementById('statsPlot').value;
        const days = document.getElementById('statsDays').value;

        try {
            const params = '?days=' + days + (plotId ? '&farmlandId=' + plotId : '');
            const r = await App.get('/api/stats/history' + params);
            const d = r.data;

            renderStatsCards(d);
            renderWaterChart(d.waterByDay || []);
            renderEnvChart(d.sensorByDay || []);

            // 决策记录仅在有明确地块时展示
            if (plotId) {
                const dr = await App.get('/api/irrigation/history/' + plotId + '?limit=50');
                renderDecisionTable(dr.data || []);
            } else {
                document.getElementById('decisionTableBody').innerHTML =
                    App.emptyRow(7, '请选择具体地块查看决策记录');
            }
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    function renderStatsCards(d) {
        const irr = d.irrigation || {};
        const sen = d.sensor || {};
        document.getElementById('statsCards').innerHTML = ''
            + statCard('灌溉次数', irr.successCount, '次', 'blue', 'fa-droplet',
                '自动 ' + (irr.autoCount || 0) + ' 次 / 手动 ' + (irr.manualCount || 0) + ' 次')
            + statCard('累计用水量', App.num(irr.totalWater), 'm³', 'purple', 'fa-glass-water',
                '单次平均 ' + App.num(irr.avgWaterPerTime) + ' m³')
            + statCard('估算节水率', App.num(irr.savingRate), '%', 'green', 'fa-leaf',
                '对比传统定时满灌（估算）')
            + statCard('平均土壤湿度', App.num(sen.avgHumidity), '%', 'green', 'fa-tint',
                '最低 ' + App.num(sen.minHumidity) + '% / 最高 ' + App.num(sen.maxHumidity) + '%')
            + statCard('平均温度', App.num(sen.avgTemperature), '℃', 'orange', 'fa-temperature-half',
                '最高 ' + App.num(sen.maxTemperature) + '℃')
            + statCard('监测采样点', sen.sampleCount, '条', 'blue', 'fa-chart-simple',
                '平均置信度 ' + App.num((d.decisions || {}).avgConfidence) + '%');
    }

    function statCard(label, value, unit, color, icon, hint) {
        return '<div class="stat">'
            + '<div class="stat-icon icon-' + color + '"><i class="fas ' + icon + '"></i></div>'
            + '<div class="label">' + label + '</div>'
            + '<div class="value">' + value + '<span class="unit">' + unit + '</span></div>'
            + '<div class="hint">' + hint + '</div>'
            + '</div>';
    }

    function renderWaterChart(list) {
        App.lineChart('waterChart', list.map(x => x.day.substring(5)), [{
            label: '灌溉用水量 (m³)',
            data: list.map(x => x.totalWater),
            borderColor: '#667eea',
            backgroundColor: 'rgba(102,126,234,.14)',
            borderWidth: 2.5,
            fill: true,
            tension: .3,
            pointRadius: 2
        }]);
    }

    function renderEnvChart(list) {
        if (!list || list.length === 0) {
            const canvas = document.getElementById('envChart');
            const ctx = canvas.getContext('2d');
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }
        const labels = list.map(x => String(x.stat_day).substring(5));
        App.lineChart('envChart', labels, [
            {
                label: '平均土壤湿度 (%)',
                data: list.map(x => x.avg_humidity),
                borderColor: '#3b82f6',
                backgroundColor: 'rgba(59,130,246,.12)',
                borderWidth: 2.5,
                fill: true,
                tension: .3
            },
            {
                label: '平均温度 (℃)',
                data: list.map(x => x.avg_temperature),
                borderColor: '#f59e0b',
                borderWidth: 2,
                fill: false,
                tension: .3,
                yAxisID: 'y1'
            }
        ], {
            scales: {
                y: { position: 'left', grid: { color: 'rgba(0,0,0,.05)' } },
                y1: { position: 'right', grid: { display: false } },
                x: { grid: { display: false } }
            }
        });
    }

    function renderDecisionTable(list) {
        const tbody = document.getElementById('decisionTableBody');
        if (list.length === 0) {
            tbody.innerHTML = App.emptyRow(7, '暂无决策记录');
            return;
        }
        tbody.innerHTML = list.map(function (x) {
            const need = x.decisionResult === '需要灌溉';
            return '<tr>'
                + '<td>' + App.fmtDateTime(x.decisionTime) + '</td>'
                + '<td>' + App.badge(x.triggerSource === 'AUTO' ? '自动' : '手动',
                    x.triggerSource === 'AUTO' ? 'purple' : 'info') + '</td>'
                + '<td>' + App.badge(x.decisionResult, need ? 'warning' : 'success') + '</td>'
                + '<td>' + App.num(x.recommendedWater) + '</td>'
                + '<td>' + (x.actualWater === null || x.actualWater === undefined ? '--' : App.num(x.actualWater)) + '</td>'
                + '<td>' + App.num(x.confidence) + '%</td>'
                + '<td class="small muted">' + App.orDash(x.reason) + '</td>'
                + '</tr>';
        }).join('');
    }

    // ==================================================================
    // 个人设置
    // ==================================================================
    async function changePassword() {
        const oldPwd = document.getElementById('oldPassword').value;
        const newPwd = document.getElementById('newPassword').value;
        if (!oldPwd || !newPwd) {
            App.toast('请填写原密码与新密码', 'warning');
            return;
        }
        try {
            const r = await App.post('/api/user/change-password', { oldPassword: oldPwd, newPassword: newPwd });
            App.toast(r.message, 'success');
            setTimeout(() => { window.location.href = '/index.html'; }, 1200);
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    async function saveProfile() {
        try {
            const r = await App.post('/api/user/profile', { phone: document.getElementById('phone').value });
            App.toast(r.message, 'success');
        } catch (e) {
            App.toast(e.message, 'error');
        }
    }

    // 页面加载
    document.addEventListener('DOMContentLoaded', function () {
        init().catch(function (e) {
            App.toast(e.message || '页面初始化失败', 'error');
        });
        // 定时刷新看板数据
        setInterval(function () {
            if (document.getElementById('view-dashboard').classList.contains('active')) {
                loadSensorData();
            }
        }, 60000);
    });

    return {
        reloadPlots, refreshSensor, mockData,
        manualIrrigate, checkDecision, cancelRecord,
        loadAutoStatus, toggleAuto,
        loadAlarms, handleAlarm,
        loadDevices, loadStats, loadFertilizer,
        changePassword, saveProfile
    };
})();
