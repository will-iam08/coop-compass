const statuses = ["SAVED", "APPLIED", "INTERVIEW", "OFFER", "REJECTED"];
const labels = { SAVED: "Saved", APPLIED: "Applied", INTERVIEW: "Interview", OFFER: "Offer", REJECTED: "Rejected" };
let applications = [];
let deletedApplications = [];
let visibleApplications = [];
let selectedIds = new Set();
let batchBusy = false;
let selectedDeletedIds = new Set();
let deletedSelectionMode = false;
let deletedBatchBusy = false;

const board = document.querySelector("#board");
const notice = document.querySelector("#notice");
const form = document.querySelector("#application-form");
const goalInput = document.querySelector("#weekly-goal");
const statusFilter = document.querySelector("#status-filter");
const sortBy = document.querySelector("#sort-by");
const selectionToolbar = document.querySelector("#selection-toolbar");
const selectionCount = document.querySelector("#selection-count");
const selectVisible = document.querySelector("#select-visible");
const batchStatus = document.querySelector("#batch-status");
const moveSelected = document.querySelector("#move-selected");
const deleteSelected = document.querySelector("#delete-selected");
const clearSelection = document.querySelector("#clear-selection");
const recentlyDeleted = document.querySelector("#recently-deleted");
const deletedCount = document.querySelector("#deleted-count");
const toggleDeletedSelection = document.querySelector("#toggle-deleted-selection");
const deletedSelectionToolbar = document.querySelector("#deleted-selection-toolbar");
const deletedSelectionCount = document.querySelector("#deleted-selection-count");
const selectAllDeleted = document.querySelector("#select-all-deleted");
const restoreSelectedDeleted = document.querySelector("#restore-selected-deleted");
const permanentlyDeleteSelected = document.querySelector("#permanently-delete-selected");
const confirmDialog = document.querySelector("#confirm-dialog");
const confirmTitle = document.querySelector("#confirm-title");
const confirmCopy = document.querySelector("#confirm-copy");
const confirmRemove = document.querySelector("#confirm-remove");
const keepApplication = document.querySelector("#keep-application");
const cancelRemove = document.querySelector("#cancel-remove");
const permanentDeleteDialog = document.querySelector("#permanent-delete-dialog");
const permanentDeleteTitle = document.querySelector("#permanent-delete-title");
const permanentDeleteCopy = document.querySelector("#permanent-delete-copy");
const confirmPermanentDelete = document.querySelector("#confirm-permanent-delete");
const keepDeleted = document.querySelector("#keep-deleted");
const cancelPermanentDelete = document.querySelector("#cancel-permanent-delete");
const storageKey = "my-internship-notebook-weekly-goal";
const retentionMs = 7 * 24 * 60 * 60 * 1000;
let resolveRemoval;
let resolvePermanentDeletion;

const escapeHtml = (value = "") => String(value).replace(/[&<>'"]/g, character => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", "'":"&#39;", '"':"&quot;" })[character]);
const dateLabel = value => value ? `Deadline: ${new Date(`${value}T12:00:00`).toLocaleDateString(undefined, { month:"short", day:"numeric" })}` : "";
const savedGoal = Number(window.localStorage.getItem(storageKey) ?? window.localStorage.getItem("coop-compass-weekly-goal"));
goalInput.value = savedGoal > 0 ? savedGoal : 6;

async function request(url, options = {}) {
  const response = await fetch(url, { headers: { "Content-Type":"application/json" }, ...options });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.error || "Could not save your change."); }
  return response.status === 204 ? null : response.json();
}

function showNotice(message, error = false) {
  notice.textContent = message; notice.hidden = false; notice.classList.toggle("error", error);
  window.clearTimeout(showNotice.timer); showNotice.timer = window.setTimeout(() => { notice.hidden = true; }, 4200);
}

function confirmRemoval(companies) {
  const names = Array.isArray(companies) ? companies : [companies];
  const count = names.length;
  confirmTitle.textContent = count === 1 ? "Move this to Recently Deleted?" : `Move ${count} applications to Recently Deleted?`;
  confirmCopy.textContent = count === 1
    ? `${names[0]} will stay recoverable in Recently Deleted for seven days.`
    : `These applications will stay recoverable in Recently Deleted for seven days.`;
  confirmRemove.textContent = count === 1 ? "Move it" : "Move them";
  confirmDialog.showModal();
  return new Promise(resolve => { resolveRemoval = resolve; });
}

function closeRemoval(confirmed) {
  if (!confirmDialog.open) return;
  confirmDialog.close();
  resolveRemoval?.(confirmed);
  resolveRemoval = undefined;
}

confirmRemove.addEventListener("click", () => closeRemoval(true));
keepApplication.addEventListener("click", () => closeRemoval(false));
cancelRemove.addEventListener("click", () => closeRemoval(false));
confirmDialog.addEventListener("cancel", event => {
  event.preventDefault();
  closeRemoval(false);
});
confirmDialog.addEventListener("click", event => {
  if (event.target === confirmDialog) closeRemoval(false);
});

function confirmPermanentDeletion(companies) {
  const names = Array.isArray(companies) ? companies : [companies];
  const count = names.length;
  permanentDeleteTitle.textContent = count === 1 ? `Delete ${names[0]} permanently?` : `Delete ${count} applications permanently?`;
  permanentDeleteCopy.textContent = count === 1
    ? "This application will be removed immediately and cannot be restored."
    : "These applications will be removed immediately and cannot be restored.";
  confirmPermanentDelete.textContent = count === 1 ? "Delete permanently" : "Delete all permanently";
  permanentDeleteDialog.showModal();
  return new Promise(resolve => { resolvePermanentDeletion = resolve; });
}

function closePermanentDeletion(confirmed) {
  if (!permanentDeleteDialog.open) return;
  permanentDeleteDialog.close();
  resolvePermanentDeletion?.(confirmed);
  resolvePermanentDeletion = undefined;
}

confirmPermanentDelete.addEventListener("click", () => closePermanentDeletion(true));
keepDeleted.addEventListener("click", () => closePermanentDeletion(false));
cancelPermanentDelete.addEventListener("click", () => closePermanentDeletion(false));
permanentDeleteDialog.addEventListener("cancel", event => {
  event.preventDefault();
  closePermanentDeletion(false);
});
permanentDeleteDialog.addEventListener("click", event => {
  if (event.target === permanentDeleteDialog) closePermanentDeletion(false);
});

function render() {
  const query = document.querySelector("#search").value.trim().toLowerCase();
  const filter = statusFilter.value;
  const visible = applications
    .filter(application => [application.company, application.role, application.location, application.skills.join(" ")].join(" ").toLowerCase().includes(query))
    .filter(application => filter === "ALL" || (filter === "ACTIVE" ? ["SAVED", "APPLIED", "INTERVIEW"].includes(application.status) : application.status === filter))
    .sort(compareApplications);
  visibleApplications = visible;
  board.innerHTML = "";
  statuses.forEach(status => {
    const entries = visible.filter(application => application.status === status);
    const column = document.createElement("section");
    column.className = "column";
    column.innerHTML = `<div class="column-header"><h3>${labels[status]}</h3><span>${entries.length}</span></div><div class="cards"></div>`;
    const cards = column.querySelector(".cards");
    if (!entries.length) cards.innerHTML = `<p class="empty">${query ? "No matches here." : "Nothing here yet."}</p>`;
    entries.forEach((application, index) => cards.append(card(application, index)));
    board.append(column);
  });
  renderSelectionToolbar();
  renderInsights();
  renderRecentlyDeleted();
}

function compareApplications(left, right) {
  if (sortBy.value === "company") return left.company.localeCompare(right.company);
  if (sortBy.value === "deadline") {
    const leftDeadline = left.deadline || "9999-12-31";
    const rightDeadline = right.deadline || "9999-12-31";
    return leftDeadline.localeCompare(rightDeadline);
  }
  return new Date(right.createdAt) - new Date(left.createdAt);
}

function card(application, index) {
  const template = document.querySelector("#card-template").content.cloneNode(true);
  const node = template.querySelector(".application-card");
  node.style.setProperty("--card-index", index);
  node.querySelector(".company").textContent = application.company;
  node.querySelector(".role").textContent = application.role;
  node.querySelector(".meta").textContent = [application.location, application.source].filter(Boolean).join(" · ");
  node.querySelector(".deadline").textContent = dateLabel(application.deadline);
  node.querySelector(".notes").textContent = application.notes;
  const tags = node.querySelector(".tags");
  application.skills.forEach(skill => { const tag = document.createElement("span"); tag.className = "tag"; tag.textContent = skill; tags.append(tag); });
  const select = node.querySelector(".status-select");
  statuses.forEach(status => { const option = new Option(labels[status], status, false, status === application.status); select.add(option); });
  select.addEventListener("change", async event => {
    try { await request(`/api/applications/${application.id}`, { method:"PATCH", body:JSON.stringify({ status:event.target.value }) }); await refresh(); showNotice("Application updated."); }
    catch (error) { showNotice(error.message, true); event.target.value = application.status; }
  });
  const selection = node.querySelector(".select-application");
  selection.checked = selectedIds.has(application.id);
  selection.disabled = batchBusy;
  selection.setAttribute("aria-label", `Select ${application.company}`);
  node.classList.toggle("is-selected", selection.checked);
  selection.addEventListener("change", () => {
    if (selection.checked) selectedIds.add(application.id);
    else selectedIds.delete(application.id);
    node.classList.toggle("is-selected", selection.checked);
    renderSelectionToolbar();
  });
  const removeButton = node.querySelector(".delete");
  removeButton.setAttribute("aria-label", `Move ${application.company} to Recently Deleted`);
  removeButton.addEventListener("click", async () => {
    const confirmed = await confirmRemoval([application.company]);
    if (confirmed !== true) return;
    try { await request(`/api/applications/${application.id}`, { method:"DELETE" }); await refresh(); showNotice("Application removed."); }
    catch (error) { showNotice(error.message, true); }
  });
  return node;
}

function selectedApplications() {
  return applications.filter(application => selectedIds.has(application.id));
}

function renderSelectionToolbar() {
  const count = selectedIds.size;
  const hasSelection = count > 0;
  const allVisibleSelected = visibleApplications.length > 0 && visibleApplications.every(application => selectedIds.has(application.id));
  selectionToolbar.classList.toggle("has-selection", hasSelection);
  selectionCount.textContent = `${count} selected`;
  selectVisible.textContent = allVisibleSelected ? "Clear visible" : "Select visible";
  selectVisible.disabled = batchBusy || visibleApplications.length === 0;
  batchStatus.disabled = !hasSelection || batchBusy;
  moveSelected.disabled = !hasSelection || batchBusy;
  deleteSelected.disabled = !hasSelection || batchBusy;
  clearSelection.disabled = !hasSelection || batchBusy;
  document.querySelectorAll(".select-application").forEach(input => { input.disabled = batchBusy; });
}

async function moveSelectedApplications() {
  const selections = selectedApplications();
  if (!selections.length) return;
  const status = batchStatus.value;
  batchBusy = true;
  renderSelectionToolbar();
  try {
    await request("/api/applications/bulk-status", {
      method:"POST",
      body:JSON.stringify({ ids:selections.map(application => application.id), status })
    });
    selectedIds.clear();
    await refresh();
    showNotice(`${selections.length} ${selections.length === 1 ? "application is" : "applications are"} now ${labels[status]}.`);
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    batchBusy = false;
    renderSelectionToolbar();
  }
}

async function deleteSelectedApplications() {
  const selections = selectedApplications();
  if (!selections.length) return;
  const confirmed = await confirmRemoval(selections.map(application => application.company));
  if (confirmed !== true) return;
  batchBusy = true;
  renderSelectionToolbar();
  try {
    await request("/api/applications/bulk-delete", {
      method:"POST",
      body:JSON.stringify({ ids:selections.map(application => application.id) })
    });
    selectedIds.clear();
    await refresh();
    showNotice(`${selections.length} ${selections.length === 1 ? "application is" : "applications are"} in Recently Deleted for seven days.`);
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    batchBusy = false;
    renderSelectionToolbar();
  }
}

function stats(dashboard) {
  document.querySelector("#total-count").textContent = dashboard.total;
  document.querySelector("#applied-count").textContent = dashboard.byStatus.APPLIED;
  document.querySelector("#interview-count").textContent = dashboard.byStatus.INTERVIEW;
  document.querySelector("#response-rate").textContent = `${dashboard.responseRate}%`;
}

function renderInsights() {
  const goal = Math.max(1, Number(goalInput.value) || 6);
  const active = applications.filter(application => ["SAVED", "APPLIED", "INTERVIEW"].includes(application.status)).length;
  const progress = Math.min(100, Math.round((active / goal) * 100));
  document.querySelector("#goal-progress").style.width = `${progress}%`;
  document.querySelector("#goal-copy").textContent = active >= goal
    ? `You’re at ${active}/${goal}. Weekly goal reached — keep the momentum.`
    : `${active}/${goal} active opportunities. Add ${goal - active} more to hit your weekly goal.`;

  const upcoming = applications
    .filter(application => application.deadline)
    .sort((left, right) => left.deadline.localeCompare(right.deadline))
    .slice(0, 3);
  const nextUp = document.querySelector("#up-next");
  nextUp.innerHTML = upcoming.length
    ? upcoming.map(application => `<div class="deadline-item"><span>${escapeHtml(dateLabel(application.deadline).replace("Deadline: ", ""))}</span><strong>${escapeHtml(application.company)}</strong><small>${escapeHtml(application.role)}</small></div>`).join("")
    : `<p class="empty-insight">No deadlines yet. Add one when you save a role.</p>`;

  const skillCounts = new Map();
  applications.flatMap(application => application.skills).forEach(skill => skillCounts.set(skill, (skillCounts.get(skill) || 0) + 1));
  const skills = [...skillCounts.entries()].sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0])).slice(0, 6);
  const topSkills = document.querySelector("#top-skills");
  topSkills.innerHTML = skills.length
    ? skills.map(([skill, count]) => `<span class="signal-tag">${escapeHtml(skill)}<b>${count}</b></span>`).join("")
    : `<p class="empty-insight">Add skill tags to spot patterns across roles.</p>`;
}

function timeRemaining(deletedAt) {
  const expiresAt = new Date(deletedAt).getTime() + retentionMs;
  const remaining = expiresAt - Date.now();
  if (!Number.isFinite(expiresAt) || remaining <= 0) return "Expires soon";
  const days = Math.ceil(remaining / (24 * 60 * 60 * 1000));
  return days === 1 ? "1 day left" : `${days} days left`;
}

function selectedDeletedApplications() {
  return deletedApplications.filter(application => selectedDeletedIds.has(application.id));
}

function renderDeletedSelectionToolbar() {
  const count = selectedDeletedIds.size;
  const hasSelection = count > 0;
  const allDeletedSelected = deletedApplications.length > 0 && deletedApplications.every(application => selectedDeletedIds.has(application.id));
  toggleDeletedSelection.textContent = deletedSelectionMode ? "Done" : "Select items";
  toggleDeletedSelection.setAttribute("aria-expanded", String(deletedSelectionMode));
  toggleDeletedSelection.disabled = deletedBatchBusy || deletedApplications.length === 0;
  deletedSelectionToolbar.hidden = !deletedSelectionMode;
  deletedSelectionToolbar.classList.toggle("has-selection", deletedSelectionMode && hasSelection);

  if (!deletedSelectionMode) return;

  deletedSelectionCount.textContent = `${count} selected`;
  selectAllDeleted.textContent = allDeletedSelected ? "Clear all" : "Select all";
  selectAllDeleted.disabled = deletedBatchBusy || deletedApplications.length === 0;
  restoreSelectedDeleted.disabled = !hasSelection || deletedBatchBusy;
  permanentlyDeleteSelected.disabled = !hasSelection || deletedBatchBusy;
  document.querySelectorAll(".select-deleted-application").forEach(input => { input.disabled = deletedBatchBusy; });
}

async function restoreSelectedDeletedApplications() {
  const selections = selectedDeletedApplications();
  if (!selections.length) return;
  deletedBatchBusy = true;
  renderDeletedSelectionToolbar();
  try {
    await request("/api/recently-deleted/bulk-restore", {
      method:"POST",
      body:JSON.stringify({ ids:selections.map(application => application.id) })
    });
    selectedDeletedIds.clear();
    await refresh();
    showNotice(`${selections.length} ${selections.length === 1 ? "application is" : "applications are"} back in your pipeline.`);
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    deletedBatchBusy = false;
    renderDeletedSelectionToolbar();
  }
}

async function permanentlyDeleteSelectedApplications() {
  const selections = selectedDeletedApplications();
  if (!selections.length) return;
  const confirmed = await confirmPermanentDeletion(selections.map(application => application.company));
  if (confirmed !== true) return;
  deletedBatchBusy = true;
  renderDeletedSelectionToolbar();
  try {
    await request("/api/recently-deleted/bulk-permanent-delete", {
      method:"POST",
      body:JSON.stringify({ ids:selections.map(application => application.id) })
    });
    selectedDeletedIds.clear();
    await refresh();
    showNotice(`${selections.length} ${selections.length === 1 ? "application was" : "applications were"} deleted permanently.`);
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    deletedBatchBusy = false;
    renderDeletedSelectionToolbar();
  }
}

function renderRecentlyDeleted() {
  const count = deletedApplications.length;
  deletedCount.textContent = `${count} ${count === 1 ? "item" : "items"}`;
  recentlyDeleted.innerHTML = "";
  renderDeletedSelectionToolbar();

  if (!count) {
    recentlyDeleted.innerHTML = `<p class="deleted-empty">Nothing here right now. If you remove an application, you’ll have seven days to bring it back.</p>`;
    return;
  }

  deletedApplications
    .slice()
    .sort((left, right) => new Date(right.deletedAt) - new Date(left.deletedAt))
    .forEach((application, index) => {
      const node = document.createElement("article");
      node.className = "deleted-card";
      node.style.setProperty("--deleted-index", index);
      const isSelected = selectedDeletedIds.has(application.id);
      node.classList.toggle("is-selected", deletedSelectionMode && isSelected);
      if (deletedSelectionMode) {
        const selection = document.createElement("input");
        selection.className = "select-deleted-application";
        selection.type = "checkbox";
        selection.checked = isSelected;
        selection.disabled = deletedBatchBusy;
        selection.setAttribute("aria-label", `Select ${application.company} from Recently Deleted`);
        selection.addEventListener("change", () => {
          if (selection.checked) selectedDeletedIds.add(application.id);
          else selectedDeletedIds.delete(application.id);
          node.classList.toggle("is-selected", selection.checked);
          renderDeletedSelectionToolbar();
        });
        node.append(selection);
      }
      const timer = document.createElement("p");
      timer.className = "deleted-timer";
      timer.textContent = `◷ ${timeRemaining(application.deletedAt)}`;
      const company = document.createElement("h3");
      company.textContent = application.company;
      const details = document.createElement("p");
      details.className = "deleted-details";
      details.textContent = [application.role, application.location].filter(Boolean).join(" · ") || "Application";
      node.append(timer, company, details);
      recentlyDeleted.append(node);
    });
  renderDeletedSelectionToolbar();
}

async function refresh() {
  const [activeApplications, dashboard, removedApplications] = await Promise.all([
    request("/api/applications"),
    request("/api/dashboard"),
    request("/api/recently-deleted")
  ]);
  applications = activeApplications;
  deletedApplications = removedApplications;
  const activeIds = new Set(applications.map(application => application.id));
  const deletedIds = new Set(deletedApplications.map(application => application.id));
  selectedIds = new Set([...selectedIds].filter(id => activeIds.has(id)));
  selectedDeletedIds = new Set([...selectedDeletedIds].filter(id => deletedIds.has(id)));
  if (!deletedApplications.length) deletedSelectionMode = false;
  stats(dashboard);
  render();
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  const fields = Object.fromEntries(new FormData(form).entries());
  try { await request("/api/applications", { method:"POST", body:JSON.stringify(fields) }); form.reset(); await refresh(); showNotice("Added to your pipeline."); }
  catch (error) { showNotice(error.message, true); }
});

document.querySelector("#search").addEventListener("input", render);
statusFilter.addEventListener("change", render);
sortBy.addEventListener("change", render);
selectVisible.addEventListener("click", () => {
  const allVisibleSelected = visibleApplications.length > 0 && visibleApplications.every(application => selectedIds.has(application.id));
  visibleApplications.forEach(application => {
    if (allVisibleSelected) selectedIds.delete(application.id);
    else selectedIds.add(application.id);
  });
  render();
});
clearSelection.addEventListener("click", () => {
  selectedIds.clear();
  render();
});
moveSelected.addEventListener("click", moveSelectedApplications);
deleteSelected.addEventListener("click", deleteSelectedApplications);
toggleDeletedSelection.addEventListener("click", () => {
  if (deletedBatchBusy || !deletedApplications.length) return;
  deletedSelectionMode = !deletedSelectionMode;
  if (!deletedSelectionMode) selectedDeletedIds.clear();
  renderRecentlyDeleted();
});
selectAllDeleted.addEventListener("click", () => {
  const allDeletedSelected = deletedApplications.length > 0 && deletedApplications.every(application => selectedDeletedIds.has(application.id));
  if (allDeletedSelected) selectedDeletedIds.clear();
  else deletedApplications.forEach(application => selectedDeletedIds.add(application.id));
  renderRecentlyDeleted();
});
restoreSelectedDeleted.addEventListener("click", restoreSelectedDeletedApplications);
permanentlyDeleteSelected.addEventListener("click", permanentlyDeleteSelectedApplications);
goalInput.addEventListener("change", () => {
  const goal = Math.max(1, Math.min(50, Number(goalInput.value) || 6));
  goalInput.value = goal;
  window.localStorage.setItem(storageKey, goal);
  renderInsights();
});
document.querySelector("#export-csv").addEventListener("click", () => {
  const columns = ["Company", "Role", "Location", "Source", "Status", "Deadline", "Skills", "Notes"];
  const quote = value => `"${String(value ?? "").replaceAll("\"", "\"\"")}"`;
  const rows = applications.map(application => [application.company, application.role, application.location, application.source, labels[application.status], application.deadline, application.skills.join(", "), application.notes].map(quote).join(","));
  const blob = new Blob([[columns.join(","), ...rows].join("\n")], { type:"text/csv;charset=utf-8" });
  const link = Object.assign(document.createElement("a"), { href:URL.createObjectURL(blob), download:"my-internship-notebook-applications.csv" });
  link.click(); URL.revokeObjectURL(link.href);
  showNotice("CSV exported — nice work keeping a record of your search.");
});
refresh().catch(error => showNotice(`Could not connect to the app: ${error.message}`, true));
