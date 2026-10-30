export const nodeDefinitions = {
  manual: { backendType: "MANUAL_TRIGGER", label: "Manual Trigger" },
  webhook: { backendType: "WEBHOOK", label: "Webhook Trigger" },
  http: { backendType: "HTTP_REQUEST", label: "HTTP Request" },
  delay: { backendType: "DELAY", label: "Delay" },
  if: { backendType: "IF", label: "IF Condition" },
  transform: { backendType: "TRANSFORM", label: "Transform Data" },
  merge: { backendType: "MERGE", label: "Merge" },
  gmail: { backendType: "GMAIL", label: "Gmail" },
  telegram: { backendType: "TELEGRAM", label: "Telegram" }
};

export const backendNodeTypes = Object.fromEntries(
  Object.entries(nodeDefinitions).map(([type, definition]) => [type, definition.backendType])
);

export const frontendNodeTypes = Object.fromEntries(
  Object.entries(nodeDefinitions).map(([type, definition]) => [definition.backendType, type])
);
