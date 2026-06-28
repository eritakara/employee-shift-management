document.addEventListener('DOMContentLoaded', () => {
  if (document.documentElement.lang === 'en') translatePage();
  const menu = document.querySelector('[data-menu]');
  if (menu) {
    const setMenuOpen = open => {
      document.body.classList.toggle('nav-open', open);
      menu.setAttribute('aria-expanded', String(open));
      if (open) document.querySelector('#main-navigation a')?.focus();
    };
    menu.addEventListener('click', (e) => {
      e.stopPropagation();
      setMenuOpen(!document.body.classList.contains('nav-open'));
    });
    document.addEventListener('click', event => {
      if (document.body.classList.contains('nav-open')) {
        const sidebar = document.querySelector('#main-navigation');
        if (sidebar && !sidebar.contains(event.target) && !menu.contains(event.target)) {
          setMenuOpen(false);
        }
      }
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && document.body.classList.contains('nav-open')) {
        setMenuOpen(false);
        menu.focus();
      }
    });
    document.querySelectorAll('#main-navigation a').forEach(link => link.addEventListener('click', () => setMenuOpen(false)));
  }

  document.querySelectorAll('form').forEach(form => {
    form.addEventListener('submit', event => {
      if (form.matches('[data-export-form]')) return;
      const submitter = event.submitter;
      const confirmMessage = submitter?.dataset.confirmMessage;
      if (confirmMessage && !window.confirm(confirmMessage)) {
        event.preventDefault();
        return;
      }
      if (form.dataset.submitting === 'true') {
        event.preventDefault();
        return;
      }
      if (!form.checkValidity()) return;
      form.dataset.submitting = 'true';
      form.setAttribute('aria-busy', 'true');
      const status = document.createElement('span');
      status.className = 'loading-indicator';
      status.setAttribute('role', 'status');
      status.textContent = document.documentElement.lang === 'en' ? 'Processing…' : '処理中…';
      form.appendChild(status);
    });
  });

  const exportForm = document.querySelector('[data-export-form]');
  if (exportForm) {
    const exportButton = exportForm.querySelector('[data-export-button]');
    const exportStatus = exportForm.querySelector('[data-export-status]');
    exportForm.addEventListener('submit', async event => {
      event.preventDefault();
      if (!exportForm.checkValidity() || exportForm.dataset.submitting === 'true') return;
      exportForm.dataset.submitting = 'true';
      exportForm.setAttribute('aria-busy', 'true');
      exportButton.disabled = true;
      exportStatus.textContent = document.documentElement.lang === 'en'
        ? 'Preparing export…' : '出力ファイルを準備しています…';
      try {
        const url = new URL(exportForm.action, window.location.href);
        url.search = new URLSearchParams(new FormData(exportForm)).toString();
        const response = await fetch(url, { credentials: 'same-origin' });
        if (!response.ok) throw new Error(`Export failed (${response.status})`);
        const blob = await response.blob();
        const disposition = response.headers.get('Content-Disposition') || '';
        const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || 'export.csv';
        const downloadUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = downloadUrl;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
        exportStatus.textContent = document.documentElement.lang === 'en'
          ? 'Download started.' : 'ダウンロードを開始しました。';
      } catch (error) {
        console.error(error);
        exportStatus.textContent = document.documentElement.lang === 'en'
          ? 'Export failed. Please try again.' : '出力に失敗しました。条件を確認して再度お試しください。';
      } finally {
        exportForm.dataset.submitting = 'false';
        exportForm.removeAttribute('aria-busy');
        exportButton.disabled = false;
      }
    });
  }

  document.querySelectorAll('[data-auto-submit]').forEach(input => {
    input.addEventListener('change', () => input.form?.requestSubmit());
  });

  const attendanceEmployeeSelect = document.querySelector('[data-attendance-employee-select]');
  if (attendanceEmployeeSelect) {
    const employeeStatus = document.querySelector('[data-attendance-employee-status]');
    const confirmAction = document.querySelector('[data-attendance-employee-confirm]');
    const confirmButton = document.querySelector('[data-attendance-employee-confirm-button]');
    const releaseAction = document.querySelector('[data-attendance-employee-release]');
    const updateEmployeeCloseAction = () => {
      const selected = attendanceEmployeeSelect.selectedOptions[0];
      const count = Number(selected?.dataset.count || 0);
      const finalized = selected?.dataset.finalized === 'true';
      if (employeeStatus) employeeStatus.textContent = count === 0 ? '対象月の勤怠データはありません' : finalized ? '状態：この従業員は確定済み' : '状態：この従業員は未確定';
      if (confirmButton) {
        confirmButton.disabled = count === 0;
        confirmButton.dataset.confirmMessage = `選択した従業員の${attendanceEmployeeSelect.dataset.displayMonth}の勤怠を確定します。よろしいですか？`;
      }
      if (confirmAction) confirmAction.hidden = finalized;
      if (releaseAction) releaseAction.hidden = !finalized;
    };
    attendanceEmployeeSelect.addEventListener('change', updateEmployeeCloseAction);
    updateEmployeeCloseAction();
  }

  const leaveRejectDialog = document.querySelector('[data-leave-reject-dialog]');
  if (leaveRejectDialog) {
    const rejectForm = leaveRejectDialog.querySelector('[data-leave-reject-form]');
    const reasonInput = leaveRejectDialog.querySelector('[data-leave-reject-reason-input]');
    const error = leaveRejectDialog.querySelector('[data-leave-reject-error]');
    const setText = (selector, value) => {
      const target = leaveRejectDialog.querySelector(selector);
      if (target) target.textContent = value?.trim() || '-';
    };
    document.querySelectorAll('[data-leave-reject-open]').forEach(button => {
      button.addEventListener('click', () => {
        rejectForm.reset();
        rejectForm.querySelector('[data-leave-reject-id]').value = button.dataset.requestId;
        setText('[data-leave-reject-requester]', button.dataset.requester);
        setText('[data-leave-reject-date]', button.dataset.leaveDate);
        setText('[data-leave-reject-reason]', button.dataset.reason);
        setText('[data-leave-reject-status]', button.dataset.status);
        error.hidden = true;
        reasonInput.setCustomValidity('');
        leaveRejectDialog.showModal();
        reasonInput.focus();
      });
    });
    leaveRejectDialog.querySelector('[data-leave-reject-cancel]')?.addEventListener('click', () => leaveRejectDialog.close());
    leaveRejectDialog.addEventListener('click', event => { if (event.target === leaveRejectDialog) leaveRejectDialog.close(); });
    reasonInput.addEventListener('input', () => {
      if (reasonInput.value.trim()) {
        error.hidden = true;
        reasonInput.setCustomValidity('');
      }
    });
    rejectForm.addEventListener('submit', event => {
      if (reasonInput.value.trim()) return;
      event.preventDefault();
      rejectForm.dataset.submitting = 'false';
      rejectForm.removeAttribute('aria-busy');
      rejectForm.querySelector('.loading-indicator')?.remove();
      error.hidden = false;
      reasonInput.setCustomValidity('却下理由を入力してください');
      reasonInput.reportValidity();
    });
  }

  const shiftRejectDialog = document.querySelector('[data-shift-reject-dialog]');
  if (shiftRejectDialog) {
    const rejectForm = shiftRejectDialog.querySelector('[data-shift-reject-form]');
    const reasonInput = shiftRejectDialog.querySelector('[data-shift-reject-reason-input]');
    const error = shiftRejectDialog.querySelector('[data-shift-reject-error]');
    const setText = (selector, value) => {
      const target = shiftRejectDialog.querySelector(selector);
      if (target) target.textContent = value?.trim() || '-';
    };
    document.querySelectorAll('[data-shift-reject-open]').forEach(button => {
      button.addEventListener('click', () => {
        rejectForm.reset();
        rejectForm.querySelector('[data-shift-reject-id]').value = button.dataset.requestId;
        rejectForm.querySelector('[data-shift-reject-return-page]').value = button.dataset.returnPage;
        setText('[data-shift-reject-requester]', button.dataset.requester);
        setText('[data-shift-reject-date]', button.dataset.shiftDate);
        setText('[data-shift-reject-before]', button.dataset.before);
        setText('[data-shift-reject-after]', button.dataset.after);
        setText('[data-shift-reject-reason]', button.dataset.reason);
        setText('[data-shift-reject-status]', button.dataset.status);
        error.hidden = true;
        reasonInput.setCustomValidity('');
        shiftRejectDialog.showModal();
        reasonInput.focus();
      });
    });
    shiftRejectDialog.querySelector('[data-shift-reject-cancel]')?.addEventListener('click', () => shiftRejectDialog.close());
    shiftRejectDialog.addEventListener('click', event => { if (event.target === shiftRejectDialog) shiftRejectDialog.close(); });
    reasonInput.addEventListener('input', () => {
      if (reasonInput.value.trim()) {
        error.hidden = true;
        reasonInput.setCustomValidity('');
      }
    });
    rejectForm.addEventListener('submit', event => {
      if (reasonInput.value.trim()) return;
      event.preventDefault();
      rejectForm.dataset.submitting = 'false';
      rejectForm.removeAttribute('aria-busy');
      rejectForm.querySelector('.loading-indicator')?.remove();
      error.hidden = false;
      reasonInput.setCustomValidity('却下理由を入力してください');
      reasonInput.reportValidity();
    });
  }

  const leaveUnitSelect = document.querySelector('form select[name="unit"]');
  if (leaveUnitSelect) {
    const leaveHoursInput = leaveUnitSelect.closest('form').querySelector('input[name="hours"]');
    if (leaveHoursInput) {
      const updateHoursState = () => {
        const isHourly = leaveUnitSelect.value === 'HOURLY';
        leaveHoursInput.disabled = !isHourly;
        leaveHoursInput.closest('label').style.opacity = isHourly ? '1' : '0.4';
        if (isHourly) {
          leaveHoursInput.required = true;
        } else {
          leaveHoursInput.required = false;
          leaveHoursInput.value = '';
        }
      };
      leaveUnitSelect.addEventListener('change', updateHoursState);
      updateHoursState();
    }
  }

  const leaveRequestForm = document.querySelector('[data-leave-request-form]');
  if (leaveRequestForm) {
    const datePicker = leaveRequestForm.querySelector('[data-leave-date-picker]');
    const addButton = leaveRequestForm.querySelector('[data-leave-date-add]');
    const datesInput = leaveRequestForm.querySelector('[data-leave-dates-input]');
    const dateList = leaveRequestForm.querySelector('[data-leave-date-list]');
    const selectedDates = new Set();
    const formatDate = value => {
      const [year, month, day] = value.split('-');
      return `${year}年${month}月${day}日`;
    };
    const renderDates = () => {
      const values = [...selectedDates].sort();
      datesInput.value = values.join(',');
      dateList.replaceChildren();
      if (!values.length) {
        const empty = document.createElement('span');
        empty.className = 'muted';
        empty.textContent = '取得日を追加してください。';
        dateList.append(empty);
        return;
      }
      values.forEach(value => {
        const chip = document.createElement('span');
        chip.className = 'leave-date-chip';
        chip.textContent = formatDate(value);
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.setAttribute('aria-label', `${formatDate(value)}を削除`);
        remove.textContent = '×';
        remove.addEventListener('click', () => {
          selectedDates.delete(value);
          renderDates();
        });
        chip.append(remove);
        dateList.append(chip);
      });
    };
    const addDate = () => {
      if (!datePicker.value) return false;
      selectedDates.add(datePicker.value);
      datePicker.value = '';
      datePicker.setCustomValidity('');
      renderDates();
      return true;
    };
    datePicker.required = false;
    datePicker.addEventListener('change', addDate);
    addButton?.addEventListener('click', addDate);
    leaveRequestForm.addEventListener('submit', event => {
      addDate();
      if (!selectedDates.size) {
        event.preventDefault();
        datePicker.setCustomValidity('取得日を選択してください。');
        datePicker.reportValidity();
        return;
      }
      datePicker.setCustomValidity('');
      renderDates();
    });
    renderDates();
  }

  document.querySelectorAll('[data-print-page]').forEach(button => {
    button.addEventListener('click', () => window.print());
  });

  const autoPrint = document.querySelector('[data-print-on-load]');
  if (autoPrint) {
    let printStarted = false;
    window.addEventListener('beforeprint', () => { printStarted = true; }, {once:true});
    window.requestAnimationFrame(() => {
      window.setTimeout(() => {
        window.focus();
        window.print();
        window.setTimeout(() => {
          if (!printStarted) autoPrint.hidden = false;
        }, 400);
      }, 100);
    });
  }

  document.querySelectorAll('[data-preference-form]').forEach(form => {
    const selects = [...form.querySelectorAll('[data-preference-select]')];
    const summary = form.querySelector('[data-preference-summary]');
    const empty = form.querySelector('[data-preference-empty]');
    const totals = [...form.querySelectorAll('[data-preference-total]')];
    const dialog = form.querySelector('[data-leave-reason-dialog]');
    const reasonText = dialog?.querySelector('[data-leave-reason-text]');
    const reasonDate = dialog?.querySelector('[data-leave-reason-date]');
    let activeLeaveSelect = null;
    const classes = ['day', 'night', 'off', 'leave', 'other'];
    const classFor = value => ({DAY:'day', NIGHT:'night', OFF:'off', LEAVE:'leave'}[value] || 'other');
    const reasonInput = select => select.closest('[data-preference-day]').querySelector('[data-preference-reason]');
    const openLeaveReason = select => {
      if (!dialog) return;
      activeLeaveSelect = select;
      reasonText.value = reasonInput(select).value;
      reasonDate.textContent = select.name.replace('preference_', '');
      dialog.showModal();
      reasonText.focus();
    };
    const refresh = () => {
      summary.replaceChildren();
      selects.forEach(select => {
        const card = select.closest('[data-preference-day]');
        card.classList.remove(...classes);
        card.classList.add(classFor(select.value));
        if (select.value === 'NONE') return;
        const item = document.createElement('li');
        const date = select.name.replace('preference_', '');
        const reason = reasonInput(select).value.trim();
        item.append(document.createTextNode(`${date}：${select.options[select.selectedIndex].dataset.label}${select.value === 'LEAVE' && reason ? `（${reason}）` : ''}`));
        if (select.value === 'LEAVE') {
          const edit = document.createElement('button');
          edit.type = 'button';
          edit.className = 'link-button';
          edit.textContent = reason ? '理由を編集' : '理由を追加';
          edit.addEventListener('click', () => openLeaveReason(select));
          item.append(' ', edit);
        }
        summary.appendChild(item);
      });
      totals.forEach(total => total.textContent = String(selects.filter(select => select.value === total.dataset.preferenceTotal).length));
      empty.hidden = summary.children.length > 0;
    };
    selects.forEach(select => select.addEventListener('change', () => {
      if (select.value !== 'LEAVE') reasonInput(select).value = '';
      refresh();
      if (select.value === 'LEAVE') openLeaveReason(select);
    }));
    dialog?.querySelector('[data-leave-reason-save]')?.addEventListener('click', () => {
      if (activeLeaveSelect) reasonInput(activeLeaveSelect).value = reasonText.value.trim();
      dialog.close(); refresh();
    });
    dialog?.querySelector('[data-leave-reason-clear]')?.addEventListener('click', () => {
      if (activeLeaveSelect) reasonInput(activeLeaveSelect).value = '';
      reasonText.value = ''; dialog.close(); refresh();
    });
    dialog?.querySelector('[data-leave-reason-cancel]')?.addEventListener('click', () => dialog.close());
    dialog?.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
    refresh();
  });

  const shiftEditor = document.querySelector('[data-shift-editor]');
  if (shiftEditor) {
    const cells = [...document.querySelectorAll('[data-shift-edit-cell]')];
    const userId = shiftEditor.querySelector('[data-shift-editor-user-id]');
    const employee = shiftEditor.querySelector('[data-shift-editor-employee]');
    const date = shiftEditor.querySelector('[data-shift-editor-date]');
    const before = shiftEditor.querySelector('[data-shift-editor-before]');
    const workType = shiftEditor.querySelector('[data-shift-editor-work-type]');
    const status = shiftEditor.querySelector('[data-shift-editor-status]');
    const note = shiftEditor.querySelector('[data-shift-editor-note]');
    cells.forEach(cell => cell.addEventListener('click', () => {
      cells.forEach(item => {
        item.classList.toggle('selected', item === cell);
        item.setAttribute('aria-pressed', String(item === cell));
      });
      userId.value = cell.dataset.userId;
      employee.value = cell.dataset.employee;
      date.value = cell.dataset.date;
      before.textContent = cell.dataset.workTypeLabel || '未登録';
      workType.value = cell.dataset.workType || workType.options[0]?.value || '';
      status.value = cell.dataset.status || 'DRAFT';
      note.value = cell.dataset.note || '';
      shiftEditor.hidden = false;
      shiftEditor.scrollIntoView({ behavior: 'smooth', block: 'start' });
      workType.focus({ preventScroll: true });
    }));
  }

  const clock = document.querySelector('[data-clock]');
  if (clock) {
    const update = () => clock.textContent = new Intl.DateTimeFormat(document.documentElement.lang || 'ja', {
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    }).format(new Date());
    update(); setInterval(update, 1000);
  }

  document.querySelectorAll('[data-clock-form]').forEach(form => {
    form.addEventListener('submit', event => {
      if (form.dataset.ready === 'true') return;
      event.preventDefault();
      const finish = (status, position) => {
        form.querySelector('[name=locationStatus]').value = status;
        if (position) {
          form.querySelector('[name=lat]').value = position.coords.latitude;
          form.querySelector('[name=lng]').value = position.coords.longitude;
        }
        form.dataset.ready = 'true'; form.submit();
      };
      if (!navigator.geolocation) return finish('UNAVAILABLE');
      navigator.geolocation.getCurrentPosition(p => finish('ACQUIRED', p), () => finish('DENIED'), {
        enableHighAccuracy: true, timeout: 8000, maximumAge: 0
      });
    });
  });
});

function translatePage() {
  const dictionary = {
    'ダッシュボード':'Dashboard','通知':'Notifications','アカウント設定':'Account settings',
    'シフト':'Schedule','希望シフト提出':'Submit schedule','月間シフト表':'Team schedule',
    'シフト変更・休み申請':'Schedule change request','シフト申請履歴':'Schedule request history',
    'シフト調整':'Schedule editor','シフト確定確認':'Schedule confirmation','月間シフト印刷':'Print schedule',
    '有休残数・取得履歴':'Leave balance and history','有休申請':'Leave request','有休申請履歴':'Leave request history','有休承認':'Leave approvals',
    '出勤・退勤打刻':'Time clock','自分の勤怠':'My attendance','打刻修正申請':'Attendance correction',
    '打刻修正履歴':'Correction history','勤怠確認・月次確定':'Monthly attendance close','全社勤怠確認':'Company attendance',
    '従業員一覧':'Employees','従業員登録・編集':'Employee details','資格情報管理':'Qualifications','代理店長設定':'Manager delegation',
    '営業所管理':'Branch offices','部署管理':'Departments','勤務区分・休憩時間管理':'Work types and breaks',
    '必要人数管理':'Staffing requirements','雇用形態・資格名称管理':'Employment and qualification types','データ出力':'Data export','操作履歴':'Audit log',
    '今日の勤務者':'Working today','未承認申請':'Pending approvals','有休残日数':'Leave balance','シフト充足状況':'Shift coverage','不足なし':'No shortage','不足':'Shortage','今月の実勤務':'Hours this month',
    '勤務時間・残業時間の推移':'Work and overtime trend','勤務時間・残業時間・有休取得数の推移':'Work, overtime, and leave trend','直近6か月':'Last 6 months','今月の予定':'This month','すべて見る':'View all',
    '対象月':'Month','表示':'Show','印刷':'Print','印刷する':'Print','シフトへ戻る':'Back to schedule','調整する':'Edit schedule','勤務区分を変更':'Change work type','変更・休みを申請':'Request a change',
    '従業員':'Employee','日付':'Date','勤務区分':'Work type','状態':'Status','備考・理由':'Note or reason','変更を保存':'Save change','申請する':'Submit',
    '希望を確認してから自動割当へ進みます。':'Review preferences before automatic assignment.','提出済み':'Submitted','未提出・再提出待ち':'Not submitted / awaiting resubmission','希望一覧を開く':'Open preference list','提出希望日を確認':'Review requested dates',
    'シフト充足状況':'Shift coverage','不足がある勤務区分を確認してください。':'Review work types with shortages.','確認事項の詳細を開く':'Open review details','選択中のシフトを変更':'Edit selected shift','月間シフトで選択したセルを編集します。':'Edit the cell selected in the monthly schedule.','変更前の勤務区分':'Current work type','変更後の勤務区分':'New work type','未登録':'Not assigned',
    '確定前チェック':'Pre-confirmation checks','警告はありません。':'No warnings.','種類':'Type','内容':'Details','必要':'Required','実績':'Actual','警告を確認して確定':'Confirm after reviewing warnings',
    '変更・休み申請':'Change and leave requests','申請者':'Requester','変更前':'Before','変更後':'After','理由':'Reason','緊急':'Urgent','操作':'Actions','承認':'Approve','却下':'Reject',
    '社員番号':'Employee no.','氏名':'Name','備考':'Note','対象月のシフトはありません。':'No schedules for this month.',
    '時間有休残':'Hourly leave remaining','有休を申請':'Request leave','取得日':'Leave date','取得単位':'Leave unit','時間数':'Hours','有休申請がありません。':'No leave requests.',
    '打刻時に端末の位置情報を記録します。場所による打刻制限はありません。':'Your device location is recorded when clocking. Location does not restrict clocking.',
    '出勤':'Clock in','退勤':'Clock out','打刻修正を申請':'Request correction','対象勤怠':'Attendance record','修正後の出勤':'Corrected clock-in','修正後の退勤':'Corrected clock-out',
    '勤怠実績':'Attendance Records','自分の月間勤怠を確認':'View your monthly attendance','自店舗スタッフの月間勤怠を確認':'View branch staff\'s monthly attendance','全従業員の月間勤怠を確認':'View all employees\' monthly attendance','勤務':'Shift','遅刻':'Late','早退':'Early','残業':'Overtime','位置情報':'Location','確定':'Finalize','解除':'Reopen','勤怠データはありません。':'No attendance records.',
    'すべて既読にする':'Mark all as read','通知はありません。':'No notifications.','詳細':'Details',
    '従業員を登録':'Add employee','メールアドレス':'Email','入社日':'Hire date','営業所':'Branch','部署':'Department','雇用形態':'Employment type','役割':'Role','登録して招待':'Register and invite','招待再発行':'Reissue invitation',
    '資格を登録':'Add qualification','資格名':'Qualification','有効期限':'Expiry date','登録':'Add','代理者':'Delegate','開始日':'Start date','終了日':'End date','設定':'Set',
    '項目を追加':'Add item','名称':'Name','追加':'Add','コード':'Code','日本語名':'Japanese name','英語名':'English name','開始':'Start','終了':'End','休憩':'Break','必要人数':'Required staff',
    '対象データ':'Data','形式':'Format','出力する':'Export','日時':'Date and time','実行者':'Actor','対象':'Target','対象ID':'Target ID','変更前':'Before','変更後':'After','最新300件':'Latest 300',
    '表示と言語':'Display and language','表示言語':'Language','新しいパスワード':'New password','設定を保存':'Save settings','有効':'Active','無効':'Inactive','提出済み':'Submitted','下書き':'Draft',
    '日勤':'Day','夜勤':'Night','夜勤明け':'Post-night rest','休み':'Off','有休':'Paid leave','午前休':'AM leave','午後休':'PM leave','1日':'Full day','時間単位':'Hourly'
  };
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach(node => {
    const raw = node.nodeValue;
    const trimmed = raw.trim();
    if (!trimmed) return;
    if (dictionary[trimmed]) {
      node.nodeValue = raw.replace(trimmed, dictionary[trimmed]);
      return;
    }
    if (trimmed.startsWith('不足 ') && dictionary['不足']) {
      node.nodeValue = raw.replace(trimmed, dictionary['不足'] + trimmed.substring(2));
    }
  });
  document.querySelectorAll('[placeholder]').forEach(element => {
    const value = element.getAttribute('placeholder');
    if (dictionary[value]) element.setAttribute('placeholder', dictionary[value]);
  });
}
