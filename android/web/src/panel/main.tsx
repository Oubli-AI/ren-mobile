/*
 * The agent panel's chat (assets/ren/web/panel/ in the app): the conversation on ren desktop's own agent panel
 * components. The app sends the chat once the page says it's ready.
 */
import { applyTheme } from "../theme"
import "../page.css"
import "@/index.css"
import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { PanelChat } from "./app"
import { tell } from "./model"

applyTheme()

const root = document.getElementById("root")
if (root === null) throw new Error("The panel page has no root element.")

createRoot(root).render(
  <StrictMode>
    <PanelChat />
  </StrictMode>,
)
tell({ type: "ready" })
