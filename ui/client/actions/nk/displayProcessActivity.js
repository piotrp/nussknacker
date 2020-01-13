// @flow
import HttpService from "../../http/HttpService"
import type {ThunkAction} from "../reduxTypes.flow"

export type DisplayProcessActivityAction = {
  type: "DISPLAY_PROCESS_ACTIVITY",
  comments: $FlowTODO[],
  attachments: $FlowTODO[],
}

export function displayProcessActivity(processId: string): ThunkAction {
  return (dispatch) => {
    return HttpService.fetchProcessActivity(processId).then((response) => {
      return dispatch({
        type: "DISPLAY_PROCESS_ACTIVITY",
        comments: response.data.comments,
        attachments: response.data.attachments,
      })
    })
  }
}