import React, { useCallback, useContext, useEffect, useState } from "react";
import {
  Button,
  InlineNotification,
  Select,
  SelectItem,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
  Tag,
  Tile,
} from "@carbon/react";
import { useIntl } from "react-intl";
import { useHistory } from "react-router-dom";
import SearchPatientForm from "../patient/SearchPatientForm";
import UserSessionDetailsContext from "../../UserSessionDetailsContext";
import { hasRole, Roles } from "../utils/Utils";
import BloodBankService from "./BloodBankService";
import BloodBankPager, { PAGE_SIZES, usePage } from "./BloodBankPager";
import EmergencyIssueModal from "./EmergencyIssueModal";
import EmergencyIssuePanel from "./EmergencyIssuePanel";
import IssueModal from "./IssueModal";
import ReasonModal from "./ReasonModal";
import ReserveModal from "./ReserveModal";
import { ExpiryTag } from "./BloodBankStock";
import {
  errorText,
  formatInstant,
  groupText,
  messageFor,
  productText,
} from "./bloodBankFormat";
import "./BloodBank.css";

// label and value side by side, on any screen width
const ProfileLine = ({ label, children }) => (
  <>
    <dt>{label}</dt>
    <dd>{children}</dd>
  </>
);

const resultText = (intl, result) =>
  result
    ? `${result.value} (${result.accessionNumber}, ${formatInstant(intl, result.collectedAt)})`
    : intl.formatMessage({ id: "bloodbank.profile.none" });

/**
 * Each sample's ABO and RhD, for a discrepancy: the lab sees which sample
 * says what, rather than one group that may be the wrong one.
 */
export const samplesOf = (typings) => {
  const bySample = new Map();
  (typings || []).forEach((t) => {
    const key = t.sampleId || t.accessionNumber;
    const entry = bySample.get(key) || {
      accessionNumber: t.accessionNumber,
      collectedAt: t.collectedAt,
    };
    entry[t.kind === "RHD" ? "rhd" : "abo"] = t.value;
    bySample.set(key, entry);
  });
  return Array.from(bySample.values());
};

export const ProfilePanel = ({ profile }) => {
  const intl = useIntl();
  const t = (id, values) => intl.formatMessage({ id }, values);
  if (!profile) return null;
  const { group } = profile;
  let groupTag;
  if (group.discrepancy) {
    groupTag = <Tag type="red">{t("bloodbank.profile.discrepancy")}</Tag>;
  } else if (group.confirmed) {
    groupTag = <Tag type="green">{t("bloodbank.profile.confirmed")}</Tag>;
  } else {
    groupTag = <Tag type="warm-gray">{t("bloodbank.profile.unconfirmed")}</Tag>;
  }
  return (
    <Tile className="bb-section" data-testid="bb-profile">
      <h4>{t("bloodbank.profile.title")}</h4>
      {profile.notConfigured && profile.notConfigured.length > 0 && (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          title=""
          subtitle={t("bloodbank.profile.notConfigured", {
            list: profile.notConfigured.map((k) => t(k)).join(", "),
          })}
        />
      )}
      <dl className="bb-profile-grid">
        <ProfileLine label={t("bloodbank.field.group")}>
          {group.discrepancy ? (
            <>
              {groupTag}
              <ul className="bb-profile-samples" data-testid="bb-discrepancy">
                {samplesOf(profile.typings).map((sample) => (
                  <li key={sample.accessionNumber}>
                    {t("bloodbank.profile.sampleResult", {
                      accession: sample.accessionNumber,
                      when: formatInstant(intl, sample.collectedAt),
                      abo: sample.abo || "?",
                      rhd: sample.rhd || "?",
                    })}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <>
              <strong>{groupText(intl, group.abo, group.rhd)}</strong>{" "}
              {groupTag}{" "}
              {t("bloodbank.profile.samples", {
                abo: group.aboSampleCount,
                rhd: group.rhdSampleCount,
              })}
            </>
          )}
        </ProfileLine>
        <ProfileLine label={t("bloodbank.profile.antibodyScreen")}>
          {resultText(intl, profile.antibodyScreen)}
        </ProfileLine>
        <ProfileLine label={t("bloodbank.profile.dat")}>
          {resultText(intl, profile.dat)}
        </ProfileLine>
      </dl>
      <h5 className="bb-section">{t("bloodbank.profile.crossmatches")}</h5>
      {(profile.crossmatches || []).length === 0 ? (
        <p>{t("bloodbank.profile.none")}</p>
      ) : (
        <ul>
          {profile.crossmatches.map((x) => (
            <li key={x.resultId}>
              {x.accessionNumber}: {x.value}{" "}
              <Tag type={x.compatible ? "green" : "red"} size="sm">
                {t(
                  x.compatible
                    ? "bloodbank.profile.compatible"
                    : "bloodbank.profile.incompatible",
                )}
              </Tag>{" "}
              {x.validUntil &&
                t("bloodbank.profile.validUntil", {
                  when: formatInstant(intl, x.validUntil),
                })}{" "}
              {x.linkedDonationNumber
                ? t("bloodbank.profile.linkedTo", {
                    din: x.linkedDonationNumber,
                  })
                : t("bloodbank.profile.free")}
            </li>
          ))}
        </ul>
      )}
    </Tile>
  );
};

// paged when the list is longer than one page; resetKey (a filter) goes back
// to the first page
const UnitTable = ({
  units,
  renderActions,
  renderNotes,
  emptyKey,
  resetKey,
  pagerId,
}) => {
  const intl = useIntl();
  const t = (id) => intl.formatMessage({ id });
  const paging = usePage(units, resetKey);
  return (
    <>
      <TableContainer>
        <Table size="md">
          <TableHead>
            <TableRow>
              <TableHeader>{t("bloodbank.field.din")}</TableHeader>
              <TableHeader>{t("bloodbank.field.product")}</TableHeader>
              <TableHeader>{t("bloodbank.field.group")}</TableHeader>
              <TableHeader>{t("bloodbank.field.expiresAt")}</TableHeader>
              {renderNotes && (
                <TableHeader>{t("bloodbank.candidates.checks")}</TableHeader>
              )}
              <TableHeader />
            </TableRow>
          </TableHead>
          <TableBody>
            {units.length === 0 && (
              <TableRow>
                <TableCell colSpan={6}>{t(emptyKey)}</TableCell>
              </TableRow>
            )}
            {paging.shown.map((entry) => {
              const unit = entry.unit || entry;
              return (
                <TableRow
                  key={unit.id}
                  className={entry.selectable === false ? "bb-disabled" : ""}
                >
                  <TableCell>{unit.donationNumber}</TableCell>
                  <TableCell>{productText(unit)}</TableCell>
                  <TableCell>{groupText(intl, unit.abo, unit.rhd)}</TableCell>
                  <TableCell>
                    {formatInstant(intl, unit.expiresAt)}{" "}
                    <ExpiryTag unit={unit} />
                  </TableCell>
                  {renderNotes && <TableCell>{renderNotes(entry)}</TableCell>}
                  <TableCell>{renderActions(entry)}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      {units.length > PAGE_SIZES[0] && (
        <BloodBankPager id={pagerId} paging={paging} />
      )}
    </>
  );
};

/**
 * Patient and issue: find the patient, see the blood group profile from
 * validated results, reserve a suitable unit against a crossmatch, then issue a
 * reserved unit with the bedside checks and the second-person check.
 */
const BloodBankPatientIssue = () => {
  const intl = useIntl();
  const history = useHistory();
  const t = (id, values) => intl.formatMessage({ id }, values);
  const [patient, setPatient] = useState(null);
  const [profile, setProfile] = useState(null);
  const [types, setTypes] = useState([]);
  const [productType, setProductType] = useState("");
  const [candidates, setCandidates] = useState([]);
  const [reserved, setReserved] = useState([]);
  const [notice, setNotice] = useState(null);
  const [reserveUnit, setReserveUnit] = useState(null);
  const [issueUnit, setIssueUnit] = useState(null);
  const [releaseUnit, setReleaseUnit] = useState(null);
  const [emergency, setEmergency] = useState(null);
  const [emergencyOpen, setEmergencyOpen] = useState(false);
  const { userSessionDetails } = useContext(UserSessionDetailsContext) || {};
  // the retrospective check is lab result work: Results or Validation
  const canRecordCheck =
    hasRole(userSessionDetails, Roles.RESULTS) ||
    hasRole(userSessionDetails, Roles.VALIDATION);

  const patientId = patient?.patientPK;

  useEffect(() => {
    BloodBankService.productTypes()
      .then(setTypes)
      .catch(() => setTypes([]));
  }, []);

  const load = useCallback(() => {
    if (!patientId) return;
    Promise.all([
      BloodBankService.profile(patientId),
      BloodBankService.candidates(patientId, productType),
      BloodBankService.listUnits({ patientId, status: ["RESERVED"] }),
      BloodBankService.emergencyIssues(patientId),
    ])
      .then(([p, c, r, e]) => {
        setProfile(p);
        setCandidates(c || []);
        setReserved(r || []);
        setEmergency(e || null);
      })
      .catch((e) => setNotice({ kind: "error", text: errorText(intl, e) }));
  }, [patientId, productType, intl]);

  useEffect(load, [load]);

  const done = (key, unit) => {
    setNotice({ kind: "success", text: t(key, { din: unit.donationNumber }) });
    load();
  };

  const release = (text) =>
    BloodBankService.unitDetail(releaseUnit.id)
      .then((detail) =>
        BloodBankService.releaseReservation(detail.activeReservation.id, text),
      )
      .then(() => done("bloodbank.unreserve.done", releaseUnit));

  if (!patient) {
    return (
      <div>
        <p className="bb-helper">{t("bloodbank.issue.findPatient")}</p>
        <SearchPatientForm getSelectedPatient={(p) => setPatient(p)} />
      </div>
    );
  }

  const name = [patient.lastName, patient.firstName].filter(Boolean).join(", ");

  return (
    <div>
      <div className="bb-row">
        <h3>{name}</h3>
        <Button
          kind="ghost"
          size="sm"
          onClick={() => {
            setPatient(null);
            setProfile(null);
            setCandidates([]);
            setReserved([]);
            setEmergency(null);
            setNotice(null);
          }}
        >
          {t("bloodbank.issue.changePatient")}
        </Button>
        <Button
          kind="ghost"
          size="sm"
          onClick={() => history.push(`/bloodbank/trace?patient=${patientId}`)}
        >
          {t("bloodbank.action.patientTrail")}
        </Button>
        {emergency?.enabled && (
          <Button
            kind="danger"
            size="sm"
            onClick={() => setEmergencyOpen(true)}
          >
            {t("bloodbank.emergency.button")}
          </Button>
        )}
      </div>
      {notice && (
        <InlineNotification
          kind={notice.kind}
          lowContrast
          title=""
          subtitle={notice.text}
          onClose={() => setNotice(null)}
        />
      )}
      <EmergencyIssuePanel
        issues={emergency?.issues}
        canRecord={canRecordCheck}
        onRecorded={(result) =>
          done(
            result.retrospectiveResult === "INCOMPATIBLE"
              ? "bloodbank.retro.done.incompatible"
              : "bloodbank.retro.done",
            result,
          )
        }
      />
      <ProfilePanel profile={profile} />

      <div className="bb-section">
        <h4>{t("bloodbank.reserved.title")}</h4>
        <UnitTable
          units={reserved}
          emptyKey="bloodbank.reserved.empty"
          pagerId="bb-reserved-pages"
          renderActions={(unit) => (
            <>
              <Button
                kind="primary"
                size="sm"
                onClick={() => setIssueUnit(unit)}
              >
                {t("bloodbank.action.issue")}
              </Button>{" "}
              <Button
                kind="ghost"
                size="sm"
                onClick={() => setReleaseUnit(unit)}
              >
                {t("bloodbank.action.unreserve")}
              </Button>
            </>
          )}
        />
      </div>

      <div className="bb-section">
        <h4>{t("bloodbank.candidates.title")}</h4>
        <div className="bb-row">
          <Select
            id="bb-candidates-type"
            labelText={t("bloodbank.field.productType")}
            value={productType}
            onChange={(e) => setProductType(e.target.value)}
          >
            <SelectItem value="" text={t("bloodbank.filter.all")} />
            {types.map((ty) => (
              <SelectItem key={ty.id} value={String(ty.id)} text={ty.name} />
            ))}
          </Select>
        </div>
        <UnitTable
          units={candidates}
          emptyKey="bloodbank.candidates.empty"
          resetKey={productType}
          pagerId="bb-candidates-pages"
          renderNotes={(entry) =>
            (entry.checks || [])
              .filter((c) => c.status !== "PASS")
              .map((c) => (
                <Tag
                  key={c.code}
                  className="bb-check-tag"
                  type={c.status === "FAIL" ? "red" : "warm-gray"}
                  size="sm"
                  title={messageFor(intl, c.messageKey, c.args)}
                >
                  {messageFor(intl, c.messageKey, c.args)}
                </Tag>
              ))
          }
          renderActions={(entry) => (
            <Button
              kind="secondary"
              size="sm"
              disabled={!entry.selectable}
              onClick={() => setReserveUnit(entry.unit)}
            >
              {t("bloodbank.action.reserve")}
            </Button>
          )}
        />
      </div>

      <ReserveModal
        open={!!reserveUnit}
        unit={reserveUnit}
        patientId={patientId}
        profile={profile}
        onClose={() => setReserveUnit(null)}
        onReserved={() => done("bloodbank.reserve.done", reserveUnit)}
      />
      <IssueModal
        open={!!issueUnit}
        unit={issueUnit}
        onClose={() => setIssueUnit(null)}
        onIssued={() => done("bloodbank.issueModal.done", issueUnit)}
      />
      {emergencyOpen && (
        <EmergencyIssueModal
          open
          patientId={patientId}
          onClose={() => setEmergencyOpen(false)}
          onIssued={(result, unit) => done("bloodbank.emergency.done", unit)}
        />
      )}
      <ReasonModal
        open={!!releaseUnit}
        title={
          releaseUnit
            ? t("bloodbank.unreserve.title", {
                din: releaseUnit.donationNumber,
              })
            : ""
        }
        label={t("bloodbank.field.reason")}
        onSubmit={release}
        onClose={() => setReleaseUnit(null)}
      />
    </div>
  );
};

export default BloodBankPatientIssue;
