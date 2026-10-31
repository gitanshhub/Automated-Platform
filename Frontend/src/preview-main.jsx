import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import UiPolishPreview from "./UiPolishPreview";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <UiPolishPreview />
  </StrictMode>
);
