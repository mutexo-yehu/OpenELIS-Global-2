import React, { useContext } from "react";
import { Grid, Column, Section, Tag } from "@carbon/react";
import { FormattedMessage, useIntl } from "react-intl";
import AsyncAvatar from "../../patient/photoManagement/photoAvatar/AyncAvatar";
import UserSessionDetailsContext from "../../../UserSessionDetailsContext";
import { canOpen } from "../../security/routeAccess";
import {
  openPatientResults,
  patientResultsPath,
  type PatientSearchResult,
} from "./searchService";

interface SearchOutputProps {
  patientData: PatientSearchResult[];
  loading?: boolean;
  className?: string;
}

const SearchOutput: React.FC<SearchOutputProps> = ({
  patientData,
  className = "patientHead",
}) => {
  const intl = useIntl();
  const { userSessionDetails } = useContext(UserSessionDetailsContext);
  return (
    <div>
      {patientData.map((patient) => {
        // the patient page is guarded (Reception); don't offer a link that would be refused
        const opensPatient = canOpen(
          patientResultsPath(patient.patientID),
          userSessionDetails,
        );
        return (
          <Column lg={16} md={8} sm={4} key={patient.id ?? patient.patientID}>
            <Section>
              <div>
                <Grid
                  className={
                    opensPatient
                      ? className
                      : `${className} patientHead-readonly`
                  }
                  onClick={
                    opensPatient
                      ? () => openPatientResults(patient.patientID)
                      : undefined
                  }
                  title={
                    opensPatient
                      ? undefined
                      : intl.formatMessage({ id: "search.patient.noAccess" })
                  }
                >
                  <Column lg={2} md={1}>
                    <div role="img">
                      <AsyncAvatar
                        patientId={patient.patientID ?? patient.id}
                        hasPhoto={Boolean(patient.patientID ?? patient.id)}
                        patientName={`${patient.lastName ?? ""} ${
                          patient.firstName ?? ""
                        }`}
                        size={patient.referringFacility ? 50 : 40}
                      />
                    </div>
                  </Column>
                  <Column lg={14} md={7} sm={3}>
                    <div className="tags">
                      <span className="patient-name-search">
                        <b>{`${patient.lastName ?? ""} ${
                          patient.firstName ?? ""
                        }`}</b>
                      </span>
                      <span>
                        {" "}
                        {patient.gender === "M" ? (
                          <>
                            ♂ <FormattedMessage id="patient.male" />
                          </>
                        ) : (
                          <>
                            ♀ <FormattedMessage id="patient.female" />
                          </>
                        )}{" "}
                        {patient.age || patient.dob}
                      </span>
                    </div>
                    <div className="tags">
                      <Tag size="md" type="blue">
                        <FormattedMessage id="patient.natioanalid" /> :{" "}
                        <strong>{patient.nationalId}</strong>
                      </Tag>
                      {!opensPatient && (
                        <Tag size="md" type="gray">
                          <FormattedMessage id="search.patient.noAccess" />
                        </Tag>
                      )}
                      {/* <Tag size="md" type="blue">
                        <FormattedMessage id="patient.subject.number" /> :{" "}
                        <strong>{patient.subjectNumber}</strong>
                      </Tag> */}
                    </div>
                  </Column>
                </Grid>
              </div>
            </Section>
          </Column>
        );
      })}
    </div>
  );
};

export default SearchOutput;
