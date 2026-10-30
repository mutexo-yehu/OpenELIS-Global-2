import React, { useEffect, useState } from "react";
import {
  Button,
  Heading,
  InlineLoading,
  InlineNotification,
  Section,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
  Column,
  Grid,
} from "@carbon/react";
import PageBreadCrumb from "../../common/PageBreadCrumb";
import { useIntl } from "react-intl";
import { getFromOpenElisServer } from "../../utils/Utils";
import useInAppNavigation from "../../common/useInAppNavigation";

const ENDPOINT = "/rest/analyzer/worklist/analyzers";

export const resultsHref = (analyzerId) =>
  `/AnalyzerResults?id=${encodeURIComponent(analyzerId)}`;

/** Results > Analyser results: pick an active analyser, open its results. */
const AnalyzerResultsIndex = () => {
  const intl = useIntl();
  const navigate = useInAppNavigation();
  const [analyzers, setAnalyzers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    getFromOpenElisServer(ENDPOINT, (response) => {
      const valid =
        response?.status === "success" && Array.isArray(response.data);
      setAnalyzers(valid ? response.data : []);
      setLoadFailed(!valid);
      setLoading(false);
    });
  }, []);

  return (
    <>
      <PageBreadCrumb
        breadcrumbs={[
          { label: "home.label", link: "/" },
          { label: "analyzer.navigation.results", isCurrentPage: true },
        ]}
      />
      <div className="pageContent">
        <Grid>
          <Column lg={16} md={8} sm={4}>
            <Section level={2} data-testid="analyzer-results-index">
              <Heading>
                {intl.formatMessage({ id: "analyzer.results.index.title" })}
              </Heading>
              <p>
                {intl.formatMessage({
                  id: "analyzer.results.index.description",
                })}
              </p>
              {loading ? (
                <InlineLoading
                  description={intl.formatMessage({
                    id: "analyzer.results.index.loading",
                  })}
                />
              ) : loadFailed ? (
                <InlineNotification
                  kind="error"
                  lowContrast
                  hideCloseButton
                  title={intl.formatMessage({
                    id: "analyzer.results.index.loadFailed",
                  })}
                />
              ) : analyzers.length === 0 ? (
                <p>
                  {intl.formatMessage({ id: "analyzer.results.index.empty" })}
                </p>
              ) : (
                <TableContainer>
                  <Table>
                    <TableHead>
                      <TableRow>
                        <TableHeader>
                          {intl.formatMessage({
                            id: "analyzer.results.index.column.analyzer",
                          })}
                        </TableHeader>
                        <TableHeader>
                          {intl.formatMessage({
                            id: "analyzer.results.index.column.results",
                          })}
                        </TableHeader>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {analyzers.map((analyzer) => (
                        <TableRow key={analyzer.id}>
                          <TableCell>{analyzer.name}</TableCell>
                          <TableCell>
                            <Button
                              kind="tertiary"
                              size="sm"
                              href={resultsHref(analyzer.id)}
                              onClick={navigate(resultsHref(analyzer.id))}
                            >
                              {intl.formatMessage({
                                id: "analyzer.results.index.view",
                              })}
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </Section>
          </Column>
        </Grid>
      </div>
    </>
  );
};

export default AnalyzerResultsIndex;
