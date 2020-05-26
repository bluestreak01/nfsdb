import $ from "jquery"

const queryBatchSize = 1000
const MSG_QUERY_EXPORT = "query.in.export"
const MSG_QUERY_EXEC = "query.in.exec"
const MSG_QUERY_CANCEL = "query.in.cancel"
const MSG_QUERY_RUNNING = "query.out.running"
const MSG_QUERY_OK = "query.out.ok"
const MSG_QUERY_ERROR = "query.out.error"
const MSG_QUERY_DATASET = "query.out.dataset"
const MSG_QUERY_FIND_N_EXEC = "query.build.execute"
const MSG_ACTIVE_PANEL = "active.panel"

const MSG_EDITOR_FOCUS = "editor.focus"
const MSG_EDITOR_EXECUTE = "editor.execute"
const MSG_EDITOR_EXECUTE_ALT = "editor.execute.alt"

const MSG_CHART_DRAW = "chart.draw"

export function toExportUrl(query) {
  return (
    window.location.protocol +
    "//" +
    window.location.host +
    "/exp?query=" +
    encodeURIComponent(query)
  )
}

export function setHeight(element, height) {
  element.css("height", height + "px")
}

export function createEditor(div) {
  const edit = ace.edit(div)
  edit.getSession().setMode("ace/mode/questdb")
  edit.setTheme("ace/theme/dracula")
  edit.setShowPrintMargin(false)
  edit.setDisplayIndentGuides(false)
  edit.setHighlightActiveLine(false)
  edit.$blockScrolling = Infinity

  $(window).on("resize", function () {
    edit.resize()
  })

  return edit
}

export {
  MSG_QUERY_EXPORT,
  MSG_QUERY_EXEC,
  MSG_QUERY_CANCEL,
  MSG_QUERY_RUNNING,
  MSG_QUERY_OK,
  MSG_QUERY_ERROR,
  MSG_QUERY_DATASET,
  MSG_ACTIVE_PANEL,
  MSG_QUERY_FIND_N_EXEC,
  MSG_EDITOR_FOCUS,
  MSG_EDITOR_EXECUTE,
  MSG_EDITOR_EXECUTE_ALT,
  MSG_CHART_DRAW,
  queryBatchSize,
}
