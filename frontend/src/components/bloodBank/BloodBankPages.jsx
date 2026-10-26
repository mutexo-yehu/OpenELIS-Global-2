import React from "react";
import { Column, Grid } from "@carbon/react";
import { FormattedMessage } from "react-intl";
import PageBreadCrumb from "../common/PageBreadCrumb";
import BloodBankStock from "./BloodBankStock";
import BloodBankReceive from "./BloodBankReceive";
import BloodBankPatientIssue from "./BloodBankPatientIssue";
import BloodBankTrace from "./BloodBankTrace";
import "./BloodBank.css";

// No link on the Blood Bank crumb: it is a menu section with no page of its own.
const BloodBankPage = ({ titleId, children }) => (
  <>
    <PageBreadCrumb
      breadcrumbs={[
        { label: "home.label", link: "/" },
        { label: "banner.menu.bloodbank" },
        { label: titleId },
      ]}
    />
    <Grid fullWidth={true}>
      <Column lg={16} md={8} sm={4}>
        <div className="orderLegendBody">
          <h1 className="page-heading-05">
            <FormattedMessage id={titleId} />
          </h1>
          {children}
        </div>
      </Column>
    </Grid>
  </>
);

export const BloodBankStockPage = () => (
  <BloodBankPage titleId="banner.menu.bloodbank.stock">
    <BloodBankStock />
  </BloodBankPage>
);

export const BloodBankReceivePage = () => (
  <BloodBankPage titleId="banner.menu.bloodbank.receive">
    <BloodBankReceive />
  </BloodBankPage>
);

export const BloodBankIssuePage = () => (
  <BloodBankPage titleId="banner.menu.bloodbank.issue">
    <BloodBankPatientIssue />
  </BloodBankPage>
);

export const BloodBankTracePage = () => (
  <BloodBankPage titleId="banner.menu.bloodbank.trace">
    <BloodBankTrace />
  </BloodBankPage>
);
