import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  DataTable,
  Grid,
  Column,
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
  Tag,
  TextInput,
} from "@carbon/react";
import PageBreadCrumb from "../../common/PageBreadCrumb";
import { Link } from "@carbon/react";
import { useIntl } from "react-intl";
import useInAppNavigation from "../../common/useInAppNavigation";
import {
  getFromOpenElisServer,
  postToOpenElisServerJsonResponse,
} from "../../utils/Utils";

const ENDPOINT = "/rest/analyzer/worklist";
export const REFRESH_MS = 10000;

const STATUS_TAG = { PENDING: "gray", SENT: "green", FAILED: "red" };

const hasMessage = (intl, id) =>
  Object.prototype.hasOwnProperty.call(intl.messages, id);

const errorText = (intl, response) => {
  const id = response?.messageKey;
  return id && hasMessage(intl, id)
    ? intl.formatMessage({ id })
    : intl.formatMessage({ id: "analyzer.worklist.error.actionFailed" });
};

const formatTime = (intl, iso) =>
  iso
    ? intl.formatDate(new Date(iso), {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "-";

/** Analyzers > Order worklist: orders sent to analysers through the Bridge. */
const OrderWorklist = () => {
  const intl = useIntl();
  const navigate = useInAppNavigation();
  const [rows, setRows] = useState([]);
  const [enabled, setEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState(null);
  const [labNumber, setLabNumber] = useState("");

  const load = useCallback(() => {
    getFromOpenElisServer(ENDPOINT, (response) => {
      const data = response?.status === "success" ? response.data : null;
      const valid = Array.isArray(data?.rows);
      setRows(valid ? data.rows : []);
      setEnabled(valid ? data.enabled !== false : true);
      setLoadFailed(!valid);
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const post = (url, onSuccess) => {
    setPending(true);
    setNotice(null);
    postToOpenElisServerJsonResponse(url, "{}", (response) => {
      setPending(false);
      if (response?.status !== "success") {
        setNotice({ kind: "error", title: errorText(intl, response) });
        return;
      }
      onSuccess(response);
      load();
    });
  };

  const retry = (row) =>
    post(`${ENDPOINT}/${encodeURIComponent(row.id)}/retry`, () => {});

  const send = (event) => {
    event.preventDefault();
    const value = labNumber.trim();
    if (!value) {
      return;
    }
    post(
      `${ENDPOINT}/send?accessionNumber=${encodeURIComponent(value)}`,
      () => {
        setNotice({
          kind: "success",
          title: intl.formatMessage(
            { id: "analyzer.worklist.send.queued" },
            { labNumber: value },
          ),
        });
        setLabNumber("");
      },
    );
  };

  const headers = useMemo(
    () =>
      [
        "labNumber",
        "analyzer",
        "tests",
        "status",
        "attempts",
        "lastError",
        "created",
        "sent",
        "actions",
      ].map((key) => ({
        key,
        header: intl.formatMessage({ id: `analyzer.worklist.column.${key}` }),
      })),
    [intl],
  );

  const tableRows = rows.map((row) => ({
    id: String(row.id),
    labNumber: row.accessionNumber,
    analyzer: row.analyzerName || row.analyzerId,
    tests: row.tests || (row.loincCodes || []).join(", "),
    status: (
      <Tag type={STATUS_TAG[row.status] || "gray"}>
        {intl.formatMessage({ id: `analyzer.worklist.status.${row.status}` })}
      </Tag>
    ),
    attempts: row.attempts,
    lastError: row.lastError || "-",
    created: formatTime(intl, row.createdAt),
    sent: formatTime(intl, row.sentAt),
    actions: (
      <>
        {row.status === "FAILED" && (
          <Button
            kind="ghost"
            size="sm"
            disabled={pending}
            onClick={() => retry(row)}
          >
            {intl.formatMessage({ id: "analyzer.worklist.retry" })}
          </Button>
        )}
        {row.analyzerId && (
          <Link
            href={`/AnalyzerResults?id=${encodeURIComponent(row.analyzerId)}`}
            onClick={navigate(
              `/AnalyzerResults?id=${encodeURIComponent(row.analyzerId)}`,
            )}
          >
            {intl.formatMessage({ id: "analyzer.worklist.viewResults" })}
          </Link>
        )}
      </>
    ),
  }));

  return (
    <>
      <PageBreadCrumb
        breadcrumbs={[
          { label: "home.label", link: "/" },
          { label: "analyzer.navigation.worklist", isCurrentPage: true },
        ]}
      />
      <div className="pageContent">
        <Grid>
          <Column lg={16} md={8} sm={4}>
            <Section level={2} data-testid="analyzer-order-worklist">
              <Heading>
                {intl.formatMessage({ id: "analyzer.worklist.title" })}
              </Heading>
              <p>
                {intl.formatMessage({ id: "analyzer.worklist.description" })}
              </p>
              {!enabled && (
                <InlineNotification
                  kind="warning"
                  lowContrast
                  hideCloseButton
                  title={intl.formatMessage({
                    id: "analyzer.worklist.disabled",
                  })}
                />
              )}
              {notice && (
                <InlineNotification
                  kind={notice.kind}
                  lowContrast
                  title={notice.title}
                  onCloseButtonClick={() => setNotice(null)}
                />
              )}
              <form onSubmit={send} style={{ margin: "1rem 0" }}>
                <Grid narrow>
                  <Column lg={6} md={4} sm={4}>
                    <TextInput
                      id="worklist-lab-number"
                      labelText={intl.formatMessage({
                        id: "analyzer.worklist.send.label",
                      })}
                      placeholder={intl.formatMessage({
                        id: "analyzer.worklist.send.placeholder",
                      })}
                      value={labNumber}
                      onChange={(event) => setLabNumber(event.target.value)}
                    />
                  </Column>
                  <Column lg={2} md={2} sm={4} style={{ alignSelf: "end" }}>
                    <Button
                      type="submit"
                      size="md"
                      disabled={pending || !labNumber.trim()}
                    >
                      {intl.formatMessage({
                        id: "analyzer.worklist.send.button",
                      })}
                    </Button>
                  </Column>
                </Grid>
              </form>
              {loading ? (
                <InlineLoading
                  description={intl.formatMessage({
                    id: "analyzer.worklist.loading",
                  })}
                />
              ) : loadFailed ? (
                <InlineNotification
                  kind="error"
                  lowContrast
                  hideCloseButton
                  title={intl.formatMessage({
                    id: "analyzer.worklist.loadFailed",
                  })}
                />
              ) : tableRows.length === 0 ? (
                <p>{intl.formatMessage({ id: "analyzer.worklist.empty" })}</p>
              ) : (
                <DataTable rows={tableRows} headers={headers}>
                  {({
                    rows: dataRows,
                    headers: dataHeaders,
                    getHeaderProps,
                    getRowProps,
                  }) => (
                    <TableContainer>
                      <Table>
                        <TableHead>
                          <TableRow>
                            {dataHeaders.map((header) => (
                              <TableHeader
                                key={header.key}
                                {...getHeaderProps({ header })}
                              >
                                {header.header}
                              </TableHeader>
                            ))}
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {dataRows.map((row) => (
                            <TableRow key={row.id} {...getRowProps({ row })}>
                              {row.cells.map((cell) => (
                                <TableCell key={cell.id}>
                                  {cell.value}
                                </TableCell>
                              ))}
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </DataTable>
              )}
            </Section>
          </Column>
        </Grid>
      </div>
    </>
  );
};

export default OrderWorklist;
