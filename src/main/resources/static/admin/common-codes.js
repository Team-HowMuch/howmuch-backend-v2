(() => {
    const API_BASE = "/api/v2";
    const ACCESS_TOKEN_KEY = "common-code-admin.access-token";
    const REQUEST_TOKEN_KEY = "common-code-admin.request-token";
    let currentGroup = null;

    const $ = (id) => document.getElementById(id);
    const loginPanel = $("loginPanel");
    const adminPanel = $("adminPanel");
    const groupForm = $("groupForm");
    const emptyState = $("emptyState");

    function token() {
        return sessionStorage.getItem(ACCESS_TOKEN_KEY);
    }

    function showMessage(message, type = "success") {
        const element = $("message");
        element.textContent = message;
        element.className = `message ${type}`;
    }

    function clearMessage() {
        $("message").className = "message hidden";
    }

    function setAuthenticated(authenticated) {
        loginPanel.classList.toggle("hidden", authenticated);
        adminPanel.classList.toggle("hidden", !authenticated);
        $("logoutButton").classList.toggle("hidden", !authenticated);
    }

    async function responseError(response) {
        let body = null;
        try { body = await response.json(); } catch (_) { /* empty response */ }
        return body?.error?.message || `요청에 실패했습니다. (${response.status})`;
    }

    async function refreshAccessToken() {
        const requestToken = sessionStorage.getItem(REQUEST_TOKEN_KEY);
        if (!requestToken) return false;
        const response = await fetch(`${API_BASE}/auth/token/refresh`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ requestToken })
        });
        if (!response.ok) return false;
        const body = await response.json();
        sessionStorage.setItem(ACCESS_TOKEN_KEY, body.data.accessToken);
        sessionStorage.setItem(REQUEST_TOKEN_KEY, body.data.requestToken);
        return true;
    }

    async function api(path, options = {}, retry = true) {
        const headers = new Headers(options.headers || {});
        if (!headers.has("Content-Type") && options.body) headers.set("Content-Type", "application/json");
        if (token()) headers.set("Authorization", `Bearer ${token()}`);
        const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
        if (response.status === 401 && retry && await refreshAccessToken()) return api(path, options, false);
        if (!response.ok) throw new Error(await responseError(response));
        return response.status === 204 ? null : response.json();
    }

    function logout() {
        sessionStorage.removeItem(ACCESS_TOKEN_KEY);
        sessionStorage.removeItem(REQUEST_TOKEN_KEY);
        currentGroup = null;
        setAuthenticated(false);
        groupForm.classList.add("hidden");
        emptyState.classList.remove("hidden");
        clearMessage();
    }

    async function login(event) {
        event.preventDefault();
        clearMessage();
        try {
            const response = await fetch(`${API_BASE}/auth/login`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ phoneNumber: $("phoneNumber").value, pin: $("pin").value })
            });
            if (!response.ok) throw new Error(await responseError(response));
            const body = await response.json();
            sessionStorage.setItem(ACCESS_TOKEN_KEY, body.data.accessToken);
            sessionStorage.setItem(REQUEST_TOKEN_KEY, body.data.requestToken);
            setAuthenticated(true);
            await loadGroups();
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    async function loadGroups() {
        clearMessage();
        try {
            const keyword = encodeURIComponent($("keyword").value.trim());
            const body = await api(`/admin/common-codes/groups?keyword=${keyword}`);
            const groups = body.data || [];
            $("groupCount").textContent = `${groups.length}개`;
            $("groupList").innerHTML = groups.map(group => `
                <button class="group-item ${currentGroup?.groupCode === group.groupCode ? "selected" : ""}" data-group-code="${escapeHtml(group.groupCode)}" type="button">
                    <strong>${escapeHtml(group.groupName)}</strong>
                    <span>${escapeHtml(group.groupCode)} · 상세 ${group.detailCount}개</span>
                </button>`).join("");
            document.querySelectorAll(".group-item").forEach(button => {
                button.addEventListener("click", () => loadGroup(button.dataset.groupCode));
            });
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    async function loadGroup(groupCode) {
        clearMessage();
        try {
            const body = await api(`/admin/common-codes/groups/${encodeURIComponent(groupCode)}`);
            currentGroup = body.data;
            renderGroup();
            await loadGroups();
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    function renderGroup() {
        if (!currentGroup) return;
        emptyState.classList.add("hidden");
        groupForm.classList.remove("hidden");
        $("editorTitle").textContent = `${currentGroup.groupName} 편집`;
        $("revisionLabel").textContent = `revision ${currentGroup.revision}`;
        $("groupCode").value = currentGroup.groupCode;
        $("groupCode").readOnly = true;
        $("groupName").value = currentGroup.groupName || "";
        $("groupDescription").value = currentGroup.description || "";
        $("attr1Name").value = currentGroup.attr1Name || "";
        $("attr2Name").value = currentGroup.attr2Name || "";
        $("attr3Name").value = currentGroup.attr3Name || "";
        $("groupActive").checked = currentGroup.active;
        $("detailRows").innerHTML = currentGroup.details.map(detailRow).join("");
    }

    function detailRow(detail = {}) {
        const existing = Boolean(detail.code);
        return `<tr class="detail-row">
            <td><input name="sortOrder" type="number" min="0" value="${detail.sortOrder ?? 0}"></td>
            <td><input class="code-input" name="code" maxlength="50" value="${escapeHtml(detail.code || "")}" ${existing ? "readonly" : "required"}></td>
            <td><input name="name" maxlength="100" value="${escapeHtml(detail.name || "")}" required></td>
            <td><input name="description" maxlength="500" value="${escapeHtml(detail.description || "")}"></td>
            <td><input name="attr1" maxlength="100" value="${escapeHtml(detail.attr1 || "")}"></td>
            <td><input name="attr2" maxlength="100" value="${escapeHtml(detail.attr2 || "")}"></td>
            <td><input name="attr3" maxlength="100" value="${escapeHtml(detail.attr3 || "")}"></td>
            <td class="active-cell"><input name="active" type="checkbox" ${detail.active !== false ? "checked" : ""}></td>
            <td class="delete-cell"><button class="delete-button" type="button">삭제</button></td>
        </tr>`;
    }

    function addDetail() {
        $("detailRows").insertAdjacentHTML("beforeend", detailRow());
        $("detailRows").lastElementChild.querySelector("input[name='code']").focus();
    }

    function collectDetails() {
        return [...document.querySelectorAll(".detail-row")].map(row => ({
            code: row.querySelector("[name='code']").value,
            name: row.querySelector("[name='name']").value,
            description: row.querySelector("[name='description']").value || null,
            sortOrder: Number(row.querySelector("[name='sortOrder']").value),
            attr1: row.querySelector("[name='attr1']").value || null,
            attr2: row.querySelector("[name='attr2']").value || null,
            attr3: row.querySelector("[name='attr3']").value || null,
            active: row.querySelector("[name='active']").checked
        }));
    }

    async function saveGroup(event) {
        event.preventDefault();
        clearMessage();
        try {
            const body = await api(`/admin/common-codes/groups/${encodeURIComponent(currentGroup.groupCode)}`, {
                method: "PUT",
                body: JSON.stringify({
                    revision: currentGroup.revision,
                    groupName: $("groupName").value,
                    description: $("groupDescription").value || null,
                    attr1Name: $("attr1Name").value || null,
                    attr2Name: $("attr2Name").value || null,
                    attr3Name: $("attr3Name").value || null,
                    active: $("groupActive").checked,
                    details: collectDetails()
                })
            });
            currentGroup = body.data;
            renderGroup();
            await loadGroups();
            showMessage("저장했습니다.");
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    async function createGroup() {
        const groupCode = window.prompt("그룹 코드를 입력하세요.");
        if (groupCode === null) return;
        const groupName = window.prompt("그룹명을 입력하세요.");
        if (groupName === null) return;
        try {
            const body = await api("/admin/common-codes/groups", {
                method: "POST",
                body: JSON.stringify({ groupCode, groupName, active: true })
            });
            currentGroup = body.data;
            renderGroup();
            await loadGroups();
            showMessage("그룹을 생성했습니다.");
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    function escapeHtml(value) {
        return String(value).replace(/[&<>'"]/g, character => ({
            "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
        }[character]));
    }

    $("loginForm").addEventListener("submit", login);
    $("logoutButton").addEventListener("click", logout);
    $("searchForm").addEventListener("submit", event => { event.preventDefault(); loadGroups(); });
    $("newGroupButton").addEventListener("click", createGroup);
    $("addDetailButton").addEventListener("click", addDetail);
    $("groupForm").addEventListener("submit", saveGroup);
    $("detailRows").addEventListener("click", event => {
        if (event.target.closest(".delete-button")) {
            event.target.closest(".detail-row").remove();
        }
    });
    $("cancelButton").addEventListener("click", () => { if (currentGroup) renderGroup(); });

    if (token()) {
        setAuthenticated(true);
        loadGroups();
    }
})();
