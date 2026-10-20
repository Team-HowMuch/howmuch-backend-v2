(() => {
  'use strict';
  const root = document.getElementById('howmuch-demo-console');
  const q = id => root.querySelector('#hm-' + id);
  const selects = [...root.querySelectorAll('[data-phone]')];
  const order = [0, 2, 3, 1];
  const ACCESS = 'howmuch.amateur.admin.access';
  const REFRESH = 'howmuch.amateur.admin.refresh';
  const PENDING = 'howmuch.amateur.pending-round';
  const currency = value => Number(value).toLocaleString('ko-KR') + '원';
  let snapshot = null, available = false, busy = false, polling = null, refreshing = null;
  let pending = null, retryPending = false, guideOpen = false;
  const ACTION_PENDING = 'howmuch.amateur.pending-action';
  let actionPending = null, notificationKind = 'SETTLEMENT_REQUEST';
  try { actionPending = JSON.parse(sessionStorage.getItem(ACTION_PENDING) || 'null'); } catch { sessionStorage.removeItem(ACTION_PENDING); }
  try { pending = JSON.parse(sessionStorage.getItem(PENDING) || 'null'); } catch { sessionStorage.removeItem(PENDING); }

  function text(tag, value, className) {
    const node = document.createElement(tag);
    node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  function loggedOut(message = '') {
    available = false;
    sessionStorage.removeItem(ACCESS); sessionStorage.removeItem(REFRESH);
    q('workspace').hidden = true; q('login').hidden = false; q('logout').hidden = true;
    q('login-message').textContent = message;
    snapshot = null;
  }
  async function api(path, options = {}, retry = true) {
    const headers = new Headers(options.headers);
    headers.set('Authorization', 'Bearer ' + (sessionStorage.getItem(ACCESS) || ''));
    if (options.body) headers.set('Content-Type', 'application/json');
    let response;
    try { response = await fetch('/console/api/' + path, {...options, headers, signal: AbortSignal.timeout(65000)}); }
    catch { const error = new Error('서버 응답을 확인하지 못했어요. 준비 요청은 결과 확인을 먼저 해주세요.'); error.code = 'NETWORK_UNCONFIRMED'; throw error; }
    if (response.status === 401 && retry && sessionStorage.getItem(REFRESH)) {
      if (!refreshing) refreshing = fetch('/console/api/refresh', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({requestToken: sessionStorage.getItem(REFRESH)}), signal: AbortSignal.timeout(12000)
      }).then(async result => {
        const value = await result.json();
        if (!result.ok || !value.data?.accessToken) { loggedOut('관리자 로그인이 필요해요.'); throw new Error('관리자 로그인이 필요해요.'); }
        sessionStorage.setItem(ACCESS, value.data.accessToken); sessionStorage.setItem(REFRESH, value.data.requestToken);
      }).finally(() => { refreshing = null; });
      await refreshing;
      return api(path, options, false);
    }
    const value = await response.json();
    if (!response.ok) {
      if ([401, 403].includes(response.status)) loggedOut(value.error?.message || '관리자 로그인이 필요해요.');
      const error = new Error(value.error?.message || '요청을 완료하지 못했어요.');
      error.code = value.error?.code; error.status = response.status;
      throw error;
    }
    return value.data;
  }
  function showLog(message) { q('log').textContent = message; }
  function controls() {
    const current = snapshot?.current;
    q('prepare').disabled = busy || !available || (!!snapshot?.moneyProtected && (!pending || retryPending)) || (!pending && !['COMPLETE', 'CHANGED'].includes(current?.status));
    q('prepare').textContent = busy ? '처리 중…' : pending ? (retryPending ? '같은 요청 다시 시도' : '처리 결과 확인') : current?.status === 'CHANGED' ? '기준 회차 새로 준비' : '다음 회차 준비';
    selects.forEach(select => { select.disabled = busy || !available; });
    q('refresh').disabled = busy;
    root.querySelectorAll('[data-create-scenario], [data-send-action]').forEach(button => { button.disabled = busy || !available || !!actionPending || (button.hasAttribute('data-create-scenario') && snapshot?.moneyProtected); });
    q('send-notification').disabled = busy || !available || !!actionPending || !q('notify-target').value;
    q('action-retry').disabled = busy || !available;
    q('action-retry').hidden = !actionPending;
    q('prepare').disabled ||= !!actionPending;
    q('start-guide').disabled = busy || !available;
    q('start-guide').textContent = ['guide', 'money'].includes(snapshot?.phones?.[1]) ? '설명 시연 마치기' : '설명 시연 시작';
  }
  function renderGuide() {
    q('phone-guide').hidden = !guideOpen;
    if (!snapshot) return;
    const c = snapshot.current;
    q('phone-guide').textContent = c.status === 'COMPLETE'
      ? '이 회차의 압축은 완료됐어요. 다음 회차를 준비한 뒤 문채원·최수아 폰에서 그룹 목록을 새로고침하고 새 모임을 선택하세요.'
      : `문채원 폰: 그룹 → 목록 새로고침 → ${c.groupName} 선택 → 내역 → 압축 정산. 앱에서 직접 실행하고 웹에서는 상태를 확인해요. 다른 폰을 자동으로 이동시키지 않아요.`;
  }
  function render() {
    if (!snapshot) return;
    q('workspace').hidden = false; q('login').hidden = true; q('logout').hidden = false;
    const c = snapshot.current;
    q('sync').textContent = available ? `마지막 조회 ${new Date(snapshot.updatedAt).toLocaleTimeString('ko-KR')} · 5초마다 확인` : '서버 연결 확인 필요 · 마지막 조회 값 표시 중';
    q('group').textContent = c.groupName + ' · #' + c.groupId;
    q('round-badge').textContent = c.round + '회차';
    const labels = {READY: '압축 전', COMPLETE: '압축 완료', RUNNING: '압축 처리 중', CHANGED: '기준과 다름'};
    q('netting-state').textContent = labels[c.status] || '확인 필요';
    const metrics = root.querySelectorAll('.hm-value');
    if (c.result) {
      metrics[0].textContent = `${c.expected.baseline_transfers}회 → ${c.result.compressed_transfers}회`;
      metrics[1].textContent = `${currency(c.result.baseline_amount)} → ${currency(c.result.compressed_amount)}`;
      q('metric-label').textContent = '서버에 저장된 실제 압축 결과';
    } else if (c.expected) {
      metrics[0].textContent = `${c.expected.baseline_transfers}회 → ${c.expected.compressed_transfers}회`;
      metrics[1].textContent = `${currency(c.expected.baseline_amount)} → ${currency(c.expected.compressed_amount)}`;
      q('metric-label').textContent = '현재 기준 5건의 예상값 · 압축은 앱에서 실행';
    } else {
      metrics[0].textContent = '대상 확인 필요'; metrics[1].textContent = '다시 계산 필요';
      q('metric-label').textContent = '기준과 다른 상태에서는 예상 결과를 표시하지 않아요.';
    }
    const messages = {
      READY: '기준 정산 5건이 준비됐어요.\n문채원 폰에서 현재 모임의 압축을 실행하세요.',
      COMPLETE: '압축이 완료됐어요.\n다음 회차에는 같은 네 계정으로 새 모임과 미납 정산 5건만 추가해요.',
      RUNNING: '앱에서 요청한 압축을 처리 중이에요.\n완료될 때까지 새 회차 준비를 기다려주세요.',
      CHANGED: '정산·입금 또는 압축 대상이 기준과 달라졌어요.\n기존 내역을 보존하고 기준 정산 5건으로 새 모임을 준비할 수 있어요.'
    };
    q('notice').textContent = !available ? '서버 상태를 확인하지 못했어요.\n마지막 조회 값을 표시하며 회차 준비는 잠시 멈춰요.' : busy ? '작업을 처리하고 있어요.\n응답이 끊겨도 같은 요청의 결과를 다시 확인해요.' : pending ? '이전 준비 요청의 결과를 확인해야 해요.\n새 요청을 만들지 않고 같은 요청을 계속 사용해요.' : snapshot.moneyProtected ? '홈·AI 금액을 설명 중인 폰이 있어요.\n설명을 마치고 해당 폰을 대기로 바꾸면 회차를 준비할 수 있어요.' : messages[c.status];
    q('notice').classList.toggle('hm-warning', !available || !!pending || snapshot.moneyProtected || c.status === 'CHANGED');
    q('action-help').textContent = '다른 폰을 이동시키거나 기존 회차를 초기화하지 않아요. 새 정산은 홈·AI 전체 합계에도 반영돼요.';
    selects.forEach((select, i) => { select.value = snapshot.phones[order[i]]; });
    const tbody = root.querySelector('tbody');
    tbody.replaceChildren(...c.settlements.map(s => {
      const row = document.createElement('tr');
      row.append(text('td', s.title), text('td', s.status === 'AWAITING_DEPOSIT' ? '입금 대기' : s.status === 'NETTING_IN_PROGRESS' ? '압축됨' : s.status), text('td', currency(s.amount)));
      return row;
    }));
    const heads = root.querySelectorAll('thead th'); heads[1].textContent = '상태';
    q('history').replaceChildren(...snapshot.history.map(r => {
      const row = text('div', '', 'hm-history-item');
      row.append(text('span', `${r.round}회차 · ${r.groupName} · #${r.groupId}`));
      if (r.groupId === c.groupId) row.append(text('span', '현재 회차'));
      else {
        const button = text('button', '이 회차 사용', 'hm-button hm-secondary'); button.type = 'button'; button.disabled = busy || !available;
        button.addEventListener('click', async () => {
          if (busy || actionPending || pending) return;
          busy = true; controls();
          try { await api('current-round', {method: 'POST', body: JSON.stringify({expectedGroupId: snapshot.current.groupId, groupId: r.groupId})}); }
          catch (error) { showLog(error.message); }
          finally { busy = false; await refresh(true); }
        }); row.append(button);
      }
      return row;
    }));
    if (snapshot.events.length) showLog(snapshot.events.map(event => `${new Date(event.at).toLocaleTimeString('ko-KR')} · ${event.message}`).join('\n'));
    else showLog('아직 운영 화면에서 실행한 준비 작업이 없어요.');
    renderGuide(); renderActions(); controls();
  }
  async function refresh(force = false) {
    if (polling) { await polling; if (!force) return; }
    polling = (async () => {
      try { snapshot = await api('state'); available = true; render(); }
      catch (error) {
        if (error.code === 'OPERATION_BUSY') return;
        available = false; render();
        if (snapshot) showLog(error.message);
        else q('login-message').textContent = error.message;
        controls();
      }
    })().finally(() => { polling = null; });
    await polling;
  }
  function keepPending(value) {
    pending = value; retryPending = false;
    if (value) sessionStorage.setItem(PENDING, JSON.stringify(value)); else sessionStorage.removeItem(PENDING);
  }
  async function roundResult() {
    const result = await api('requests/' + pending.requestId);
    if (result.status === 'SUCCEEDED') {
      keepPending(null); await refresh(true);
      showLog(`${result.round}회차 준비 완료 · 모임 #${result.groupId}. 폰에서 그룹 목록을 새로고침해 선택해주세요.`);
    } else {
      retryPending = true;
      showLog('아직 성공 결과를 확인하지 못했어요. 같은 요청 다시 시도를 눌러도 동일한 회차 하나만 준비해요.');
    }
  }
  q('prepare').addEventListener('click', async () => {
    if (busy || q('prepare').disabled) return;
    busy = true; render();
    try {
      if (pending && !retryPending) { await roundResult(); return; }
      if (!pending) keepPending({requestId: crypto.randomUUID(), expectedGroupId: snapshot.current.groupId, mode: snapshot.current.status === 'CHANGED' ? 'RECOVER' : 'NEXT'});
      const result = await api('rounds', {method: 'POST', body: JSON.stringify(pending)});
      if (result.status === 'SUCCEEDED') {
        keepPending(null); await refresh(true);
        showLog(`${result.round}회차 준비 완료 · 모임 #${result.groupId}. 기존 계정과 이전 회차는 유지했어요.`);
      }
    } catch (error) {
      if (error.status && error.status < 500 && !['OPERATION_BUSY', 'LOGIN_REQUIRED', 'ADMIN_REQUIRED'].includes(error.code)) keepPending(null);
      else retryPending = false;
      await refresh(true); showLog(error.message);
    } finally { busy = false; const message = q('log').textContent; render(); showLog(message); controls(); }
  });
  selects.forEach((select, i) => select.addEventListener('change', async () => {
    if (!snapshot || busy) return;
    const phones = [...snapshot.phones]; phones[order[i]] = select.value;
    busy = true; controls();
    try { await api('phones', {method: 'PUT', body: JSON.stringify({revision: snapshot.revision, phones})}); }
    catch (error) { showLog(error.message); }
    finally { busy = false; await refresh(true); }
  }));
  const features = {
    'basic-demo': ['오른쪽 두 폰 · 기본 시연 3분', '문채원: 더치페이 생성 → 영수증 생성. 최수아: 알림 열기 → 메뉴 선택 → 송금 안내.', 'PPT·영상은 모니터에서 유지하고 설명자는 오른쪽 두 폰으로 진행해요. 왼쪽 두 폰은 별도 모임에서 체험해요.'],
    receipt: ['문채원 → 최수아', '별도 설명용 모임에서 촬영 → 메뉴 확인 → 요청 → 참여 메뉴 선택 → 송금 안내까지.', '자유 체험도 별도 모임에서 진행하세요. 압축용 모임에 정산을 추가하면 기준 변경으로 표시돼요.'],
    basic: ['문채원 → 최수아', '별도 설명용 모임에서 20,000원 정산 → 각 10,000원 확인 → 요청.', '설명용·자유 체험용 모임은 앱에서 따로 선택하거나 생성해주세요.'],
    deposit: ['문채원 폰 · 별도 정산 한 건', '한 번에 한 건씩 입금 확인과 독촉을 보여주세요. 자유 체험은 송금 안내 화면까지 진행해요.', '이 화면에서는 입금을 생성하지 않아요. 압축 회차 준비는 은행 거래를 추가하지 않아요.'],
    ai: ['문채원 폰', '“아직 송금 안 한 사람 누구야?” → 답변 카드 → 정산 상세.', '금액을 설명하는 동안 해당 폰을 홈·AI 금액 설명 중으로 표시하고 다른 폰의 생성·입금도 잠시 맞춰주세요.'],
    fixed: ['원래 아마추어 모임', '고정 지출 → 스터디룸 정기 이용권 → 발행 내역.', '원래 모임의 고정 지출·완료 이력을 사용해요. 새 압축 회차에는 복사하지 않아요.'],
    recap: ['각 계정 · 2026년 9월', '리캡 → 9월 → 지난달·이번 달 지출 비교.', '기존 8·9월 리캡을 사용해요. 10월 확정 리캡은 없어요.']
  };
  const kindLabels = {SETTLEMENT_REQUEST: '정산 요청', SETTLEMENT_REMINDER: '독촉', REMINDER_TEST: '리마인드', SETTLEMENT_COMPLETED: '정산 완료', SETTLEMENT_PAYMENT_COMPLETED: '입금 확인', MONTHLY_RECAP: '리캡'};
  function setOptions(select, options, fallback = '') {
    const previous = select.value;
    select.replaceChildren(...options.map(([value, label]) => { const option = text('option', label); option.value = String(value); return option; }));
    if (options.some(([value]) => String(value) === previous)) select.value = previous;
    else if (options.some(([value]) => String(value) === fallback)) select.value = fallback;
  }
  function renderNotificationTargets() {
    if (!snapshot) return;
    const recipient = Number(q('notify-recipient').value), catalog = snapshot.catalog;
    const values = notificationKind === 'MONTHLY_RECAP'
      ? catalog.recaps.filter(r => r.userId === recipient).map(r => [r.month, r.month + ' 리캡'])
      : catalog.settlements.filter(s => {
        if (!(s.participants || []).includes(recipient)) return false;
        if (notificationKind === 'SETTLEMENT_COMPLETED') return s.status === 'COMPLETED';
        if (notificationKind === 'SETTLEMENT_PAYMENT_COMPLETED') return s.creator_id === recipient && s.deposits > 0;
        return s.creator_id !== recipient && ['REQUESTED', 'AWAITING_DEPOSIT'].includes(s.status);
      }).map(s => [s.id, s.title]);
    setOptions(q('notify-target'), values);
    q('target-label').firstChild.textContent = notificationKind === 'MONTHLY_RECAP' ? '연결할 리캡 월' : '연결할 정산';
    const device = catalog.devices.find(d => d.userId === recipient);
    q('device-note').textContent = !values.length ? '연결 가능한 내역이 없어요. 다른 폰이나 알림을 선택해주세요.' : device?.registered ? '푸시 토큰 등록됨 · 실제 폰 수신은 발송 후 직접 확인해주세요.' : '푸시 토큰 미등록 · 앱 로그인과 알림 권한을 확인해주세요. 알림함에는 기록돼요.';
    const actor = snapshot.actors.find(a => a.userId === recipient);
    q('send-notification').textContent = `${actor?.name || ''}에게 ${kindLabels[notificationKind]} 보내기`;
    q('notify-kinds').querySelectorAll('button').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.kind === notificationKind)));
    controls();
  }
  function renderActions() {
    if (!snapshot?.catalog) return;
    const catalog = snapshot.catalog;
    setOptions(q('recap-user'), [['all', '네 계정 모두'], ...snapshot.actors.map(a => [a.userId, a.name])]);
    setOptions(q('notify-recipient'), order.map(i => [snapshot.actors[i].userId, snapshot.actors[i].name]), '88');
    if (!q('notify-kinds').children.length) Object.entries(kindLabels).forEach(([kind, label]) => {
      const button = text('button', label, 'hm-button hm-secondary'); button.type = 'button'; button.dataset.kind = kind;
      button.addEventListener('click', () => { notificationKind = kind; renderNotificationTargets(); }); q('notify-kinds').append(button);
    });
    q('scenario-buttons').replaceChildren(...catalog.templates.map(template => {
      const names = template.participants.map(id => snapshot.actors.find(a => a.userId === id)?.name).join('·');
      const button = text('button', `${template.name} · ${names} · 데이터 넣기`, 'hm-button hm-secondary'); button.type = 'button'; button.dataset.createScenario = template.id;
      button.addEventListener('click', () => runAction('scenarios', {requestId: crypto.randomUUID(), templateId: template.id, sendNotifications: q('scenario-push').checked}, 'scenario-feedback'));
      return button;
    }));
    if (!q('template-creator').children.length) {
      setOptions(q('template-creator'), snapshot.actors.map(a => [a.userId, a.name]), '86');
      snapshot.actors.forEach(actor => {
        const label = text('label', '', 'hm-check'); const check = document.createElement('input'); check.type = 'checkbox'; check.value = String(actor.userId); check.checked = true;
        label.append(check, text('span', actor.name)); q('template-participants').append(label);
      });
    }
    q('created-scenarios').replaceChildren(...catalog.scenarios.map(s => text('p', `${s.groupName} · #${s.groupId}`, 'hm-muted')));
    const delivery = {PENDING: '발송 대기', PROCESSING: '발송 중', RETRY: '재시도 대기', SUCCEEDED: '제공자 접수', FAILED: '발송 실패'};
    q('notification-history').replaceChildren(...catalog.notifications.map(n => {
      const row = text('div', '', 'hm-notification-row');
      const actor = snapshot.actors.find(a => a.userId === n.recipient_user_id);
      const registered = catalog.devices.find(d => d.userId === n.recipient_user_id)?.registered;
      const failure = n.last_failure_code === 'TARGET_UNAVAILABLE' && !registered ? '앱에서 로그인·알림 허용 후 다시 보내주세요' : n.last_failure_code ? '알림 대상·연결 상태를 확인해주세요' : '';
      row.append(text('span', `${actor?.name} · ${n.title} · ${delivery[n.status] || '처리 확인 중'}${failure ? ' · ' + failure : ''}`));
      const received = catalog.received.includes(n.id);
      const button = text('button', received ? '폰 수신 확인됨' : '폰에서 받았어요', 'hm-button hm-secondary'); button.type = 'button'; button.disabled = received;
      button.addEventListener('click', async () => {
        if (busy) return;
        try { await api('received/' + n.id, {method: 'POST'}); await refresh(true); }
        catch (error) { q('notification-feedback').textContent = error.message; }
      });
      row.append(button); return row;
    }));
    if (!catalog.notifications.length) q('notification-history').append(text('p', '아직 이 화면에서 보낸 알림이 없어요.', 'hm-muted'));
    renderNotificationTargets();
  }
  function keepAction(value) {
    actionPending = value;
    if (value) sessionStorage.setItem(ACTION_PENDING, JSON.stringify(value)); else sessionStorage.removeItem(ACTION_PENDING);
  }
  async function runAction(endpoint, payload, feedback) {
    if (busy || !available || actionPending) return;
    keepAction({endpoint, payload, feedback}); await executeAction(false);
  }
  async function executeAction(checkFirst) {
    if (!actionPending || busy) return;
    const task = actionPending; busy = true; controls();
    try {
      let result = checkFirst ? await api('requests/' + task.payload.requestId) : null;
      if (result?.status !== 'SUCCEEDED') result = await api(task.endpoint, {method: 'POST', body: JSON.stringify(task.payload)});
      if (result.status === 'SUCCEEDED') {
        keepAction(null); await refresh(true);
        q(task.feedback).textContent = result.notificationId ? '알림 발송을 요청했어요. 아래 발송 결과와 실제 폰을 확인해주세요.' : result.recaps ? '리캡 준비 결과를 아래에서 확인해주세요.' : `${result.groupName} · #${result.groupId} 생성 완료. 폰에서 그룹 목록을 새로고침해주세요.`;
        if (result.recaps) renderRecaps(result.recaps);
      }
    } catch (error) {
      const safe = ['INVALID_SCENARIO', 'INVALID_NOTIFICATION', 'TARGET_UNAVAILABLE', 'TARGET_ALREADY_PAID', 'RECAP_UNAVAILABLE', 'MONEY_VIEW_BUSY', 'TEMPLATE_UNAVAILABLE', 'INVALID_RECAP', 'INVALID_RECAP_MONTH'];
      if (safe.includes(error.code)) keepAction(null);
      q(task.feedback).textContent = error.message + (actionPending ? ' 이전 작업 결과 확인 버튼으로 같은 요청을 이어갈 수 있어요.' : '');
    } finally { busy = false; controls(); }
  }
  q('action-retry').addEventListener('click', () => executeAction(true));
  q('notify-recipient').addEventListener('change', renderNotificationTargets);
  q('send-notification').addEventListener('click', () => {
    if (q('send-notification').disabled) return;
    runAction('notifications', {requestId: crypto.randomUUID(), type: notificationKind, recipientId: Number(q('notify-recipient').value), settlementId: notificationKind === 'MONTHLY_RECAP' ? null : q('notify-target').value, month: notificationKind === 'MONTHLY_RECAP' ? q('notify-target').value : null}, 'notification-feedback');
  });
  q('template-kind').addEventListener('change', () => {
    const kind = q('template-kind').value;
    q('template-amount').disabled = kind !== 'BASIC';
    if (kind !== 'BASIC') q('template-amount').value = kind === 'RECEIPT' ? '24000' : kind === 'GROUP' ? '0' : '308000';
    else if (Number(q('template-amount').value) < 100) q('template-amount').value = '20000';
    q('template-creator').disabled = kind === 'NETTING';
    if (kind === 'NETTING') q('template-creator').value = '86';
    q('template-participants').querySelectorAll('input').forEach(input => { input.disabled = kind === 'NETTING'; if (kind === 'NETTING') input.checked = true; });
  });
  q('template-form').addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !snapshot) return;
    const body = {name: q('template-name').value, kind: q('template-kind').value, title: q('template-title').value, amount: Number(q('template-amount').value), creatorId: Number(q('template-creator').value), participants: [...q('template-participants').querySelectorAll('input:checked')].map(input => Number(input.value))};
    busy = true; controls();
    try { await api('templates', {method: 'POST', body: JSON.stringify(body)}); await refresh(true); q('scenario-feedback').textContent = '시나리오를 저장했어요. 위의 데이터 넣기 버튼으로 바로 사용할 수 있어요.'; }
    catch (error) { q('scenario-feedback').textContent = error.message; }
    finally { busy = false; controls(); }
  });
  const stepLists = {
    'basic-demo': ['문채원 폰 · 더치페이 생성 (45초)', '문채원 폰 · 영수증 촬영·요청 (50초)', '최수아 폰 · 알림·메뉴 선택·송금 안내 (60초)', '마무리 · 질문 또는 추가 기능 (25초)'],
    receipt: ['문채원 · 영수증 요청 또는 준비한 카페 모임 선택', '최수아 · 알림에서 메뉴 선택', '최수아 · 수량과 금액 확인 → 송금 안내'],
    basic: ['문채원 · 금액과 참여자 선택', '문채원 · 각자 부담액 확인 → 요청', '최수아 · 알림 확인 → 송금 안내'],
    deposit: ['별도 정산과 수취인 확인', '해당 폰에서 입금 내역 새로고침', '정산 현황과 미납자 독촉 확인'],
    ai: ['다른 폰의 생성·입금을 잠시 맞추기', '문채원 · 미납자 질문', '답변 카드 → 실제 정산 상세'],
    fixed: ['원래 아마추어 모임 → 고정 지출', '참여 중·미참여 설정 비교', '발행 내역과 다음 일정 확인'],
    recap: ['아래에서 9월 리캡 준비 결과 확인', '폰 · 리캡 → 9월', '카드 넘기기 → 지난달과 비교']
  };
  let guideStep = 0;
  function feature() {
    q('feature-content').replaceChildren(...features[q('feature').value].map((value, i) => text(i === 0 ? 'h3' : 'p', value, i === 2 ? 'hm-muted' : '')));
    q('guide-steps').replaceChildren(...stepLists[q('feature').value].map((label, i) => { const item = text('li', label); if (i === guideStep) item.setAttribute('aria-current', 'step'); return item; }));
  }
  q('next-step').addEventListener('click', () => { guideStep = (guideStep + 1) % stepLists[q('feature').value].length; feature(); });
  q('start-guide').addEventListener('click', async () => {
    if (!snapshot || busy || !available) return;
    const phones = [...snapshot.phones], ending = ['guide', 'money'].includes(phones[1]);
    phones[1] = ending ? 'idle' : q('feature').value === 'ai' ? 'money' : 'guide'; phones[3] = ending ? 'idle' : 'guide';
    busy = true; controls();
    try { await api('phones', {method: 'PUT', body: JSON.stringify({revision: snapshot.revision, phones})}); guideStep = 0; feature(); }
    catch (error) { showLog(error.message); }
    finally { busy = false; await refresh(true); }
  });
  function renderRecaps(results) {
    q('recap-results').replaceChildren(...results.map(result => {
      const section = text('section', '', 'hm-recap-result');
      const labels = {EXISTING: '기존 리캡 유지', CREATED: '확정 리캡 생성', PREVIEW: '미리보기 · 저장 안 함', NO_COMPLETED_DATA: '완료 내역 없음'};
      section.append(text('h3', result.name + ' · ' + labels[result.status]));
      const recap = result.data;
      if (recap) {
        section.append(text('p', `${recap.yearMonth} · 완료 ${recap.completedSettlementCount}건 · 본인 지출 ${currency(recap.totalOwnAmount)}`));
        if (recap.categories?.length) section.append(text('p', recap.categories.map(c => `${c.category.name} ${currency(c.totalOwnAmount)}`).join(' · ')));
        if (recap.companion) section.append(text('p', '가장 많이 함께한 사람 · ' + recap.companion.displayName));
        if (recap.record?.fastestSettlement) section.append(text('p', '가장 빠른 정산 · ' + recap.record.fastestSettlement.title));
      }
      return section;
    }));
  }
  function recapAction(mode) {
    if (!snapshot || busy || !available) return;
    const users = q('recap-user').value === 'all' ? snapshot.actors.map(a => a.userId) : [Number(q('recap-user').value)];
    runAction('recaps', {requestId: crypto.randomUUID(), userIds: users, yearMonth: q('recap-month').value, mode}, 'recap-feedback');
  }
  q('recap-generate').addEventListener('click', () => recapAction('GENERATE'));
  q('recap-preview').addEventListener('click', () => recapAction('PREVIEW'));
  q('feature').addEventListener('change', () => { guideStep = 0; feature(); }); feature();
  q('guide').addEventListener('click', () => { guideOpen = !guideOpen; renderGuide(); });
  q('refresh').addEventListener('click', () => refresh(true));
  q('logout').addEventListener('click', () => loggedOut('이 브라우저의 관리자 인증을 지웠어요.'));
  q('login-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('button'); button.disabled = true;
    const body = {phoneNumber: q('login-phone').value.trim(), pin: q('login-pin').value}; q('login-pin').value = '';
    try {
      const response = await fetch('/console/api/login', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body), signal: AbortSignal.timeout(15000)});
      const value = await response.json();
      if (!response.ok) throw new Error(value.error?.message || '관리자 로그인을 확인해주세요.');
      sessionStorage.setItem(ACCESS, value.data.accessToken); sessionStorage.setItem(REFRESH, value.data.requestToken);
      q('login-message').textContent = ''; await refresh(true);
    } catch (error) { q('login-message').textContent = error.message; }
    finally { button.disabled = false; }
  });
  if (sessionStorage.getItem(ACCESS)) refresh();
  setInterval(() => { if (!document.hidden && !busy && sessionStorage.getItem(ACCESS)) refresh(); }, 5000);
})();
