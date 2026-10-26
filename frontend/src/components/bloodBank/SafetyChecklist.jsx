import React from "react";
import { InlineNotification, Tag } from "@carbon/react";
import {
  CheckmarkFilled,
  ErrorFilled,
  Time,
  WarningAltFilled,
} from "@carbon/icons-react";
import { useIntl } from "react-intl";
import { messageFor } from "./bloodBankFormat";
import "./BloodBank.css";

const LOOK = {
  PASS: { Icon: CheckmarkFilled, tag: "green", className: "bb-check-pass" },
  WARN: {
    Icon: WarningAltFilled,
    tag: "warm-gray",
    className: "bb-check-warn",
  },
  FAIL: { Icon: ErrorFilled, tag: "red", className: "bb-check-fail" },
  PENDING: { Icon: Time, tag: "gray", className: "bb-check-pending" },
};

/**
 * The server's safety checks, in the server's order, each with an icon and its
 * status spelled out (colour is never the only signal). A summary banner says
 * whether the issue is blocked or needs a documented reason.
 */
const SafetyChecklist = ({ checks, showSummary = true }) => {
  const intl = useIntl();
  if (!checks || checks.length === 0) return null;
  const failed = checks.filter((c) => c.status === "FAIL").length;
  const warned = checks.filter((c) => c.status === "WARN").length;
  return (
    <div className="bb-checklist" data-testid="bb-checklist">
      {showSummary && failed > 0 && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title={intl.formatMessage({ id: "bloodbank.checks.blocked.title" })}
          subtitle={intl.formatMessage(
            { id: "bloodbank.checks.blocked" },
            { count: failed },
          )}
        />
      )}
      {showSummary && failed === 0 && warned > 0 && (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          title={intl.formatMessage({ id: "bloodbank.checks.warning.title" })}
          subtitle={intl.formatMessage({ id: "bloodbank.checks.warning" })}
        />
      )}
      <ul>
        {checks.map((check) => {
          const look = LOOK[check.status] || LOOK.PENDING;
          const { Icon } = look;
          return (
            <li
              key={check.code}
              className={look.className}
              data-testid={`bb-check-${check.code}`}
            >
              <Icon size={20} aria-hidden="true" />
              <Tag type={look.tag} size="sm">
                {intl.formatMessage({
                  id: `bloodbank.checkStatus.${check.status}`,
                })}
              </Tag>
              <strong>
                {intl.formatMessage({
                  id: `bloodbank.checkCode.${check.code}`,
                })}
              </strong>
              <span>{messageFor(intl, check.messageKey, check.args)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
};

export default SafetyChecklist;
