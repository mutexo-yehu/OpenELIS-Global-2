import React, { useEffect, useState } from "react";
import { InlineNotification, Modal, TextArea } from "@carbon/react";
import { useIntl } from "react-intl";
import { errorText } from "./bloodBankFormat";

/**
 * A modal that asks for a reason (or note) and runs onSubmit(text), which
 * returns a promise. Stays open and shows the server's message on failure.
 */
const ReasonModal = ({
  open,
  title,
  label,
  helper,
  required = true,
  danger = false,
  primaryText,
  onSubmit,
  onClose,
}) => {
  const intl = useIntl();
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (open) {
      setText("");
      setError(null);
      setSending(false);
    }
  }, [open]);

  const submit = () => {
    setSending(true);
    setError(null);
    onSubmit(text.trim())
      .then(() => onClose())
      .catch((e) => setError(errorText(intl, e)))
      .finally(() => setSending(false));
  };

  return (
    <Modal
      open={open}
      danger={danger}
      modalHeading={title}
      primaryButtonText={
        primaryText || intl.formatMessage({ id: "bloodbank.action.confirm" })
      }
      secondaryButtonText={intl.formatMessage({
        id: "bloodbank.action.cancel",
      })}
      primaryButtonDisabled={sending || (required && !text.trim())}
      onRequestSubmit={submit}
      onRequestClose={onClose}
    >
      {helper && <p className="bb-helper">{helper}</p>}
      <TextArea
        id="bb-reason"
        labelText={label || ""}
        value={text}
        maxCount={500}
        enableCounter
        onChange={(e) => setText(e.target.value)}
      />
      {error && (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          title={intl.formatMessage({ id: "bloodbank.error.title" })}
          subtitle={error}
        />
      )}
    </Modal>
  );
};

export default ReasonModal;
