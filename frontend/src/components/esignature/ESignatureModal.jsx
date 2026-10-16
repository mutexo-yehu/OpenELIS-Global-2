import React, { useState, useEffect, useContext } from "react";
import {
  ComposedModal,
  ModalHeader,
  ModalBody,
  ModalFooter,
  Button,
  TextInput,
  TextArea,
  Checkbox,
  InlineNotification,
  Loading,
  Stack,
} from "@carbon/react";
import { FormattedMessage, useIntl } from "react-intl";
import UserSessionDetailsContext from "../../UserSessionDetailsContext";
import {
  isEsigEnabled,
  isUserCertified,
  getSessionStatus,
  executeSignature,
  certifyUser,
  SignatureMeaning,
  DEFAULT_CERTIFICATION_TEXT,
  ESignatureError,
} from "./api";

/**
 * ESignatureModal - Modal component for electronic signature ceremony.
 *
 * Handles three flows per 21 CFR Part 11:
 * 1. First-use certification (§11.100(c)) - User must certify before first signature
 * 2. Full authentication - First signature in session requires username + password
 * 3. Password-only - Subsequent signatures in session require only password
 *
 * Props:
 * - open: boolean - Whether the modal is open
 * - onClose: function - Called when modal is closed (cancelled)
 * - onSuccess: function(signature) - Called when signature is successfully executed
 * - meaning: string - The signature meaning (AUTHORED, VALIDATED_AND_RELEASED, REJECTED)
 * - context: string - Description of what is being signed (displayed to user)
 * - recordType: string - The type of record being signed (e.g., "RESULT")
 * - recordId: number - The ID of the record being signed
 */
const ESignatureModal = ({
  open,
  onClose,
  onSuccess,
  meaning,
  context,
  recordType,
  recordId,
}) => {
  const intl = useIntl();
  const { userSessionDetails } = useContext(UserSessionDetailsContext);

  // Flow state
  const [flowStep, setFlowStep] = useState("loading"); // loading, certification, fullAuth, passwordOnly
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  // Form state
  const [enteredUsername, setEnteredUsername] = useState("");
  const [password, setPassword] = useState("");
  // single sign-on users also give the code from their authenticator app
  const [otp, setOtp] = useState("");
  const [otpRequired, setOtpRequired] = useState(false);
  const [rejectionReason, setRejectionReason] = useState("");
  const [certificationAcknowledged, setCertificationAcknowledged] =
    useState(false);

  // Session state
  // sessionStatus kept in state for potential future UI display (e.g., signing count badge)
  const [, setSessionStatus] = useState(null);

  // Get current username from session
  const userName = userSessionDetails?.loginName || "";

  // Determine the flow when modal opens
  useEffect(() => {
    if (open && userName) {
      determineFlow();
    }
  }, [open, userName]);

  // Reset state when modal closes
  useEffect(() => {
    if (!open) {
      setFlowStep("loading");
      setEnteredUsername("");
      setPassword("");
      setOtp("");
      setRejectionReason("");
      setCertificationAcknowledged(false);
      setError(null);
      setSessionStatus(null);
    }
  }, [open]);

  const determineFlow = async () => {
    setIsLoading(true);
    setError(null);

    try {
      // whether Keycloak checks this user's signature (then a code is needed too)
      const status = await isEsigEnabled().catch(() => null);
      setOtpRequired(!!status?.otpRequired);

      // Check if user is certified (using username)
      const certStatus = await isUserCertified(userName);

      if (!certStatus.certified) {
        setFlowStep("certification");
        setIsLoading(false);
        return;
      }

      // Check session status (using username)
      const session = await getSessionStatus(userName);
      setSessionStatus(session);

      if (session.sessionActive && session.signingCount > 0) {
        // Subsequent signature - password only
        // Always use the authoritative username from context, not the
        // server-echoed value, to prevent identity mismatch on submit
        setEnteredUsername(userName);
        setFlowStep("passwordOnly");
      } else {
        // First signature in session - full auth
        // Pre-fill with session username per FRS §7.1 (editable)
        setEnteredUsername(userName);
        setFlowStep("fullAuth");
      }
    } catch (err) {
      setError(
        err.message ||
          intl.formatMessage({
            id: "esig.error.loadingStatus",
            defaultMessage: "Failed to load signature status",
          }),
      );
      setFlowStep("fullAuth"); // Default to full auth on error
    } finally {
      setIsLoading(false);
    }
  };

  // the authenticator code is required when Keycloak checks the signature;
  // reports the error and returns true when it is missing
  const otpMissing = () => {
    if (otpRequired && !otp.trim()) {
      setError(
        intl.formatMessage({
          id: "esig.error.otpRequired",
          defaultMessage: "The code from your authenticator app is required",
        }),
      );
      return true;
    }
    return false;
  };

  const handleCertification = async () => {
    if (!certificationAcknowledged) {
      setError(
        intl.formatMessage({
          id: "esig.error.mustAcknowledge",
          defaultMessage: "You must acknowledge the certification statement",
        }),
      );
      return;
    }

    if (!password) {
      setError(
        intl.formatMessage({
          id: "esig.error.passwordRequired",
          defaultMessage: "Password is required",
        }),
      );
      return;
    }

    if (otpMissing()) {
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      await certifyUser({
        username: userName,
        password,
        otp: otpRequired ? otp.trim() : undefined,
        certificationText: DEFAULT_CERTIFICATION_TEXT,
      });

      // After certification, proceed to signature (a code is valid only once)
      setPassword("");
      setOtp("");
      setFlowStep("fullAuth");
    } catch (err) {
      handleApiError(err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleSignature = async () => {
    // Validate username is provided (required in both modes)
    if (!enteredUsername.trim()) {
      setError(
        intl.formatMessage({
          id: "esig.error.usernameRequired",
          defaultMessage: "Username is required",
        }),
      );
      return;
    }

    if (!password) {
      setError(
        intl.formatMessage({
          id: "esig.error.passwordRequired",
          defaultMessage: "Password is required",
        }),
      );
      return;
    }

    if (otpMissing()) {
      return;
    }

    if (meaning === SignatureMeaning.REJECTED && !rejectionReason.trim()) {
      setError(
        intl.formatMessage({
          id: "esig.error.rejectionReasonRequired",
          defaultMessage: "Rejection reason is required",
        }),
      );
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      const signature = await executeSignature({
        username: enteredUsername.trim(),
        password,
        otp: otpRequired ? otp.trim() : undefined,
        signatureMeaning: meaning,
        recordType,
        recordId,
        rejectionReason:
          meaning === SignatureMeaning.REJECTED ? rejectionReason : null,
      });

      if (onSuccess) {
        onSuccess(signature);
      }
      onClose();
    } catch (err) {
      handleApiError(err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleApiError = (err) => {
    if (err instanceof ESignatureError) {
      switch (err.code) {
        case "INVALID_REQUEST":
          setError(
            err.message ||
              intl.formatMessage({
                id: "esig.error.invalidCredentials",
                defaultMessage: "Invalid credentials",
              }),
          );
          break;
        case "ESIG_DISABLED":
          setError(
            intl.formatMessage({
              id: "esig.error.disabled",
              defaultMessage: "Electronic signatures are not enabled",
            }),
          );
          break;
        case "VERIFICATION_UNAVAILABLE":
          setError(
            intl.formatMessage({
              id: "esig.error.verificationUnavailable",
              defaultMessage:
                "Your signature could not be verified just now. Try again in a moment; if it keeps failing, contact support.",
            }),
          );
          break;
        case "NOT_IMPLEMENTED":
          setError(
            intl.formatMessage({
              id: "esig.error.keycloakNotSupported",
              defaultMessage:
                "Keycloak authentication is not yet supported for electronic signatures",
            }),
          );
          break;
        default:
          setError(
            err.message ||
              intl.formatMessage({
                id: "esig.error.generic",
                defaultMessage: "An error occurred",
              }),
          );
      }
    } else {
      setError(
        err.message ||
          intl.formatMessage({
            id: "esig.error.generic",
            defaultMessage: "An error occurred",
          }),
      );
    }
  };

  const getMeaningLabel = () => {
    switch (meaning) {
      case SignatureMeaning.AUTHORED:
        return intl.formatMessage({
          id: "esig.meaning.authored",
          defaultMessage: "Authored",
        });
      case SignatureMeaning.MODIFIED:
        return intl.formatMessage({
          id: "esig.meaning.modified",
          defaultMessage: "Modified",
        });
      case SignatureMeaning.VALIDATED_AND_RELEASED:
        return intl.formatMessage({
          id: "esig.meaning.validatedAndReleased",
          defaultMessage: "Validated and Released",
        });
      case SignatureMeaning.REJECTED:
        return intl.formatMessage({
          id: "esig.meaning.rejected",
          defaultMessage: "Rejected",
        });
      default:
        return meaning;
    }
  };

  const getModalTitle = () => {
    if (flowStep === "certification") {
      return intl.formatMessage({
        id: "esig.title.certification",
        defaultMessage: "Electronic Signature Certification",
      });
    }
    return intl.formatMessage({
      id: "esig.title.signature",
      defaultMessage: "Electronic Signature",
    });
  };

  // the code from the authenticator app, under the password, for single sign-on users
  const renderOtpInput = (id) =>
    otpRequired && (
      <TextInput
        id={id}
        labelText={intl.formatMessage({
          id: "esig.label.otp",
          defaultMessage: "Authenticator code",
        })}
        placeholder={intl.formatMessage({
          id: "esig.placeholder.otp",
          defaultMessage: "6-digit code from your authenticator app",
        })}
        value={otp}
        onChange={(e) => setOtp(e.target.value)}
        invalid={!!error && !otp.trim()}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={8}
      />
    );

  const renderCertificationContent = () => (
    <Stack gap={5}>
      <p>
        <FormattedMessage
          id="esig.certification.intro"
          defaultMessage="Before you can use electronic signatures, you must certify that your electronic signature is the legally binding equivalent of your handwritten signature."
        />
      </p>

      <div className="esig-certification-text">
        <p
          style={{
            fontStyle: "italic",
            padding: "1rem",
            backgroundColor: "#f4f4f4",
          }}
        >
          {DEFAULT_CERTIFICATION_TEXT}
        </p>
      </div>

      <Checkbox
        id="certification-acknowledgement"
        labelText={intl.formatMessage({
          id: "esig.certification.acknowledge",
          defaultMessage: "I have read and understand the above statement",
        })}
        checked={certificationAcknowledged}
        onChange={(_, { checked }) => setCertificationAcknowledged(checked)}
      />

      <TextInput
        id="certification-password"
        type="password"
        labelText={intl.formatMessage({
          id: "esig.label.password",
          defaultMessage: "Password",
        })}
        placeholder={intl.formatMessage({
          id: "esig.placeholder.password",
          defaultMessage: "Enter your password to confirm",
        })}
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        invalid={!!error && !password}
      />
      {renderOtpInput("certification-otp")}
    </Stack>
  );

  const renderSignatureContent = () => (
    <Stack gap={5}>
      {context && (
        <p>
          <strong>
            <FormattedMessage
              id="esig.label.context"
              defaultMessage="Action:"
            />
          </strong>{" "}
          {context}
        </p>
      )}

      <p>
        <strong>
          <FormattedMessage
            id="esig.label.meaning"
            defaultMessage="Signature Meaning:"
          />
        </strong>{" "}
        {getMeaningLabel()}
      </p>

      {flowStep === "fullAuth" && (
        <p style={{ fontSize: "0.875rem", color: "#525252" }}>
          <FormattedMessage
            id="esig.info.fullAuth"
            defaultMessage="This is your first signature in this session. Please enter your username and password to authenticate."
          />
        </p>
      )}

      {flowStep === "passwordOnly" && (
        <p style={{ fontSize: "0.875rem", color: "#525252" }}>
          <FormattedMessage
            id="esig.info.passwordOnly"
            defaultMessage="You have an active signing session. Enter your password to continue."
          />
        </p>
      )}

      {flowStep === "passwordOnly" ? (
        <div
          style={{
            padding: "0.75rem 1rem",
            backgroundColor: "#e0e0e0",
            borderRadius: "4px",
            fontSize: "0.875rem",
          }}
        >
          <strong>{userName}</strong>
        </div>
      ) : (
        <TextInput
          id="signature-username"
          labelText={intl.formatMessage({
            id: "esig.label.username",
            defaultMessage: "Username",
          })}
          placeholder={intl.formatMessage({
            id: "esig.placeholder.username",
            defaultMessage: "Enter your username",
          })}
          value={enteredUsername}
          onChange={(e) => setEnteredUsername(e.target.value)}
          invalid={!!error && !enteredUsername.trim()}
          autoFocus
        />
      )}

      <TextInput
        id="signature-password"
        type="password"
        labelText={intl.formatMessage({
          id: "esig.label.password",
          defaultMessage: "Password",
        })}
        placeholder={intl.formatMessage({
          id: "esig.placeholder.password",
          defaultMessage: "Enter your password to confirm",
        })}
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        invalid={!!error && !password}
        autoFocus={flowStep === "passwordOnly"}
      />
      {renderOtpInput("signature-otp")}

      {meaning === SignatureMeaning.REJECTED && (
        <TextArea
          id="rejection-reason"
          labelText={intl.formatMessage({
            id: "esig.label.rejectionReason",
            defaultMessage: "Rejection Reason",
          })}
          placeholder={intl.formatMessage({
            id: "esig.placeholder.rejectionReason",
            defaultMessage: "Enter the reason for rejection",
          })}
          value={rejectionReason}
          onChange={(e) => setRejectionReason(e.target.value)}
          invalid={!!error && !rejectionReason.trim()}
          rows={3}
        />
      )}

      <p style={{ fontSize: "0.75rem", color: "#6f6f6f" }}>
        <FormattedMessage
          id="esig.info.legalBinding"
          defaultMessage="By entering your credentials, you are creating a legally binding electronic signature per 21 CFR Part 11."
        />
      </p>
    </Stack>
  );

  const renderContent = () => {
    if (flowStep === "loading") {
      return (
        <div
          style={{ display: "flex", justifyContent: "center", padding: "2rem" }}
        >
          <Loading withOverlay={false} small />
        </div>
      );
    }

    if (flowStep === "certification") {
      return renderCertificationContent();
    }

    return renderSignatureContent();
  };

  const handleSubmit = () => {
    if (flowStep === "certification") {
      handleCertification();
    } else {
      handleSignature();
    }
  };

  const getSubmitLabel = () => {
    if (flowStep === "certification") {
      return intl.formatMessage({
        id: "esig.button.certify",
        defaultMessage: "Certify & Continue",
      });
    }
    return intl.formatMessage({
      id: "esig.button.sign",
      defaultMessage: "Sign",
    });
  };

  return (
    <ComposedModal open={open} onClose={onClose} size="sm">
      <ModalHeader
        title={getModalTitle()}
        label={
          flowStep !== "certification"
            ? intl.formatMessage(
                {
                  id: "esig.label.signingAs",
                  defaultMessage: "Signing as: {userName}",
                },
                { userName },
              )
            : undefined
        }
      />
      <ModalBody>
        {error && (
          <InlineNotification
            kind="error"
            title={intl.formatMessage({
              id: "esig.error.title",
              defaultMessage: "Error",
            })}
            subtitle={error}
            lowContrast
            onClose={() => setError(null)}
            style={{ marginBottom: "1rem" }}
          />
        )}
        {renderContent()}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" onClick={onClose} disabled={isLoading}>
          <FormattedMessage id="label.button.cancel" defaultMessage="Cancel" />
        </Button>
        <Button
          kind="primary"
          onClick={handleSubmit}
          disabled={
            isLoading ||
            flowStep === "loading" ||
            (flowStep === "certification" &&
              certificationAcknowledged === false)
          }
        >
          {isLoading ? <Loading withOverlay={false} small /> : getSubmitLabel()}
        </Button>
      </ModalFooter>
    </ComposedModal>
  );
};

export default ESignatureModal;
