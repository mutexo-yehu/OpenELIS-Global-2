import React, { useCallback, useContext, useEffect, useState } from "react";
import {
  Button,
  Checkbox,
  DataTable,
  InlineLoading,
  InlineNotification,
  OverflowMenu,
  OverflowMenuItem,
  Select,
  SelectItem,
  Tab,
  TabList,
  Tabs,
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
import UserSessionDetailsContext from "../../UserSessionDetailsContext";
import { hasRole, Roles } from "../utils/Utils";
import BloodBankService from "./BloodBankService";
import BloodBankPager, { usePage } from "./BloodBankPager";
import ReasonModal from "./ReasonModal";
import {
  durationText,
  errorText,
  formatInstant,
  groupText,
  productText,
  returnOutcome,
  statusText,
} from "./bloodBankFormat";
import "./BloodBank.css";

const IN_STOCK = ["AVAILABLE", "RESERVED", "QUARANTINED"];
const ISSUED = ["ISSUED"];

// Row actions per unit status; the server decides whether each is allowed.
const ACTIONS = {
  AVAILABLE: ["quarantine", "discard", "returnToSupplier"],
  RESERVED: ["quarantine", "discard"],
  QUARANTINED: ["release", "discard", "returnToSupplier"],
  ISSUED: ["return", "transfused"],
};

export const ExpiryTag = ({ unit }) => {
  const intl = useIntl();
  if (unit.expired) {
    return (
      <Tag type="gray" size="sm">
        {intl.formatMessage({ id: "bloodbank.stock.tag.expired" })}
      </Tag>
    );
  }
  if (unit.expiringSoon) {
    return (
      <Tag type="red" size="sm">
        {intl.formatMessage({ id: "bloodbank.stock.tag.expiringSoon" })}
      </Tag>
    );
  }
  return null;
};

const SummaryTiles = ({ summary }) => {
  const intl = useIntl();
  if (!summary) return null;
  const byType = {};
  summary.rows.forEach((row) => {
    const name = row.productType?.name || "?";
    (byType[name] = byType[name] || []).push(row);
  });
  return (
    <div className="bb-tiles" data-testid="bb-summary">
      {Object.entries(byType).map(([name, rows]) => (
        <Tile key={name}>
          <h4>{name}</h4>
          {rows.map((row) => (
            <div className="bb-tile-line" key={`${row.abo}-${row.rhd}`}>
              <span>{groupText(intl, row.abo, row.rhd)}</span>
              <strong>{row.count}</strong>
            </div>
          ))}
        </Tile>
      ))}
      <Tile>
        <h4>{intl.formatMessage({ id: "bloodbank.stock.summary.expiry" })}</h4>
        <div className="bb-tile-line">
          <Tag type="red" size="sm">
            {intl.formatMessage(
              { id: "bloodbank.stock.summary.expiringSoon" },
              { hours: summary.expiryWarnHours },
            )}
          </Tag>
          <strong>{summary.expiringSoon}</strong>
        </div>
        <div className="bb-tile-line">
          <Tag type="gray" size="sm">
            {intl.formatMessage({ id: "bloodbank.stock.summary.expired" })}
          </Tag>
          <strong>{summary.expired}</strong>
        </div>
      </Tile>
    </div>
  );
};

const BloodBankStock = () => {
  const intl = useIntl();
  const history = useHistory();
  const { userSessionDetails } = useContext(UserSessionDetailsContext);
  // release from quarantine is a supervisor action (VALIDATION or ADMIN)
  const canRelease =
    hasRole(userSessionDetails, Roles.VALIDATION) ||
    hasRole(userSessionDetails, Roles.GLOBAL_ADMIN);

  const [tab, setTab] = useState(0);
  const [types, setTypes] = useState([]);
  const [filters, setFilters] = useState({
    productType: "",
    abo: "",
    rhd: "",
    expiringOnly: false,
  });
  const [units, setUnits] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState(null);
  const [action, setAction] = useState(null); // { name, unit, at }
  // what a return will do depends on the time: keep the menu up to date
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    BloodBankService.productTypes()
      .then(setTypes)
      .catch(() => setTypes([]));
  }, []);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      BloodBankService.listUnits({
        status: tab === 0 ? IN_STOCK : ISSUED,
        productType: filters.productType,
        abo: filters.abo,
        rhd: filters.rhd,
        expiringWithinHours:
          filters.expiringOnly && summary ? summary.expiryWarnHours : undefined,
      }),
      BloodBankService.summary(),
    ])
      .then(([list, sum]) => {
        setUnits(list || []);
        setSummary(sum);
      })
      .catch((e) => setNotice({ kind: "error", text: errorText(intl, e) }))
      .finally(() => setLoading(false));
    // summary is read for the warn hours only; reloading on it would loop
  }, [tab, filters, intl]);

  useEffect(load, [load]);

  const runAction = (name, unit, text) => {
    let call;
    if (name === "return") call = BloodBankService.returnUnit(unit.id, text);
    else if (name === "transfused")
      call = BloodBankService.transfused(unit.id, text);
    else call = BloodBankService.unitAction(unit.id, name, text);
    return call.then((result) => {
      setNotice({
        kind: "success",
        text: intl.formatMessage(
          { id: "bloodbank.stock.action.done" },
          {
            din: unit.donationNumber,
            status: statusText(intl, result.unit.status),
          },
        ),
      });
      load();
    });
  };

  const headers = [
    {
      key: "donationNumber",
      header: intl.formatMessage({ id: "bloodbank.field.din" }),
    },
    {
      key: "product",
      header: intl.formatMessage({ id: "bloodbank.field.product" }),
    },
    {
      key: "group",
      header: intl.formatMessage({ id: "bloodbank.field.group" }),
    },
    {
      key: "expires",
      header: intl.formatMessage({ id: "bloodbank.field.expiresAt" }),
    },
    {
      key: "status",
      header: intl.formatMessage({ id: "bloodbank.field.status" }),
    },
    {
      key: "supplier",
      header: intl.formatMessage({ id: "bloodbank.field.supplier" }),
    },
    { key: "actions", header: "" },
  ];
  const paging = usePage(units, `${tab}|${JSON.stringify(filters)}`);
  const byId = Object.fromEntries(units.map((u) => [String(u.id), u]));
  const rows = paging.shown.map((u) => ({
    id: String(u.id),
    donationNumber: u.donationNumber,
    product: productText(u),
    group: groupText(intl, u.abo, u.rhd),
    expires: formatInstant(intl, u.expiresAt),
    status: statusText(intl, u.status),
    supplier: u.supplier || "",
    actions: "",
  }));

  const actionText = (name, unit) => {
    if (name === "return") {
      const outcome = returnOutcome(unit, now);
      if (outcome?.late) {
        return intl.formatMessage(
          { id: "bloodbank.action.returnLate" },
          { minutes: outcome.windowMinutes },
        );
      }
    }
    return intl.formatMessage({ id: `bloodbank.action.${name}` });
  };

  const modalText = (name, unit, at) => {
    const din = unit?.donationNumber;
    const t = (id, values) => intl.formatMessage({ id }, values);
    switch (name) {
      case "return": {
        // worked out when the pop-up opened; the server decides on submit
        const outcome = returnOutcome(unit, at);
        if (!outcome) {
          return {
            title: t("bloodbank.return.title", { din }),
            label: t("bloodbank.field.reasonOptional"),
            helper: t("bloodbank.return.helper"),
            required: false,
          };
        }
        const values = {
          din,
          ago: durationText(intl, outcome.minutesOut),
          minutes: outcome.windowMinutes,
        };
        return {
          title: t(
            outcome.late
              ? "bloodbank.return.titleLate"
              : "bloodbank.return.title",
            { din },
          ),
          label: t("bloodbank.field.reasonOptional"),
          helper: t(
            outcome.late
              ? "bloodbank.return.issuedAgoLate"
              : "bloodbank.return.issuedAgo",
            values,
          ),
          primaryText: t(
            outcome.late
              ? "bloodbank.return.submitLate"
              : "bloodbank.return.submit",
          ),
          danger: outcome.late,
          required: false,
        };
      }
      case "transfused":
        return {
          title: t("bloodbank.transfused.title", { din }),
          label: t("bloodbank.transfused.reactionNote"),
          helper: t("bloodbank.transfused.helper"),
          required: false,
        };
      case "release":
        return {
          title: t("bloodbank.action.release.title", { din }),
          label: t("bloodbank.field.reasonOptional"),
          required: false,
        };
      default:
        return {
          title: t(`bloodbank.action.${name}.title`, { din }),
          label: t("bloodbank.field.reason"),
          required: true,
          danger: name === "discard",
        };
    }
  };
  const modal = action ? modalText(action.name, action.unit, action.at) : null;

  return (
    <div>
      <SummaryTiles summary={summary} />
      {notice && (
        <InlineNotification
          kind={notice.kind}
          lowContrast
          title=""
          subtitle={notice.text}
          onClose={() => setNotice(null)}
        />
      )}
      <Tabs
        selectedIndex={tab}
        onChange={({ selectedIndex }) => setTab(selectedIndex)}
      >
        <TabList
          aria-label={intl.formatMessage({ id: "banner.menu.bloodbank.stock" })}
        >
          <Tab>{intl.formatMessage({ id: "bloodbank.stock.tab.inStock" })}</Tab>
          <Tab>{intl.formatMessage({ id: "bloodbank.stock.tab.issued" })}</Tab>
        </TabList>
      </Tabs>
      <div className="bb-row bb-section">
        <Select
          id="bb-filter-type"
          labelText={intl.formatMessage({ id: "bloodbank.field.productType" })}
          value={filters.productType}
          onChange={(e) =>
            setFilters({ ...filters, productType: e.target.value })
          }
        >
          <SelectItem
            value=""
            text={intl.formatMessage({ id: "bloodbank.filter.all" })}
          />
          {types.map((t) => (
            <SelectItem key={t.id} value={String(t.id)} text={t.name} />
          ))}
        </Select>
        <Select
          id="bb-filter-abo"
          labelText={intl.formatMessage({ id: "bloodbank.field.abo" })}
          value={filters.abo}
          onChange={(e) => setFilters({ ...filters, abo: e.target.value })}
        >
          <SelectItem
            value=""
            text={intl.formatMessage({ id: "bloodbank.filter.all" })}
          />
          {["O", "A", "B", "AB"].map((g) => (
            <SelectItem key={g} value={g} text={g} />
          ))}
        </Select>
        <Select
          id="bb-filter-rhd"
          labelText={intl.formatMessage({ id: "bloodbank.field.rhd" })}
          value={filters.rhd}
          onChange={(e) => setFilters({ ...filters, rhd: e.target.value })}
        >
          <SelectItem
            value=""
            text={intl.formatMessage({ id: "bloodbank.filter.all" })}
          />
          {["POS", "NEG"].map((r) => (
            <SelectItem
              key={r}
              value={r}
              text={intl.formatMessage({ id: `bloodbank.rhd.${r}` })}
            />
          ))}
        </Select>
        <Checkbox
          id="bb-filter-expiring"
          labelText={intl.formatMessage(
            { id: "bloodbank.filter.expiringOnly" },
            { hours: summary ? summary.expiryWarnHours : 48 },
          )}
          checked={filters.expiringOnly}
          onChange={(_, { checked }) =>
            setFilters({ ...filters, expiringOnly: checked })
          }
        />
        <Button kind="tertiary" size="md" onClick={load}>
          {intl.formatMessage({ id: "bloodbank.action.refresh" })}
        </Button>
        {loading && <InlineLoading />}
      </div>
      <DataTable rows={rows} headers={headers}>
        {({
          rows: tableRows,
          headers: tableHeaders,
          getHeaderProps,
          getTableProps,
        }) => (
          <TableContainer>
            <Table {...getTableProps()} size="md">
              <TableHead>
                <TableRow>
                  {tableHeaders.map((header) => (
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
                {tableRows.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={headers.length}>
                      {intl.formatMessage({ id: "bloodbank.stock.empty" })}
                    </TableCell>
                  </TableRow>
                )}
                {tableRows.map((row) => {
                  const unit = byId[row.id];
                  // DataTable renders its previous rows once after a reload;
                  // skip any row whose unit is no longer in the list
                  if (!unit) return null;
                  const names = (ACTIONS[unit.status] || []).filter(
                    (n) => n !== "release" || canRelease,
                  );
                  return (
                    <TableRow key={row.id}>
                      {row.cells.map((cell) => {
                        if (cell.info.header === "expires") {
                          return (
                            <TableCell key={cell.id}>
                              {cell.value} <ExpiryTag unit={unit} />
                            </TableCell>
                          );
                        }
                        if (cell.info.header === "actions") {
                          return (
                            <TableCell key={cell.id}>
                              <OverflowMenu
                                flipped
                                size="sm"
                                aria-label={intl.formatMessage({
                                  id: "bloodbank.action.menu",
                                })}
                                iconDescription={intl.formatMessage({
                                  id: "bloodbank.action.menu",
                                })}
                              >
                                {names.map((n) => (
                                  <OverflowMenuItem
                                    key={n}
                                    itemText={actionText(n, unit)}
                                    isDelete={n === "discard"}
                                    onClick={() =>
                                      setAction({
                                        name: n,
                                        unit,
                                        at: Date.now(),
                                      })
                                    }
                                  />
                                ))}
                                <OverflowMenuItem
                                  itemText={intl.formatMessage({
                                    id: "bloodbank.action.trail",
                                  })}
                                  onClick={() =>
                                    history.push(
                                      `/bloodbank/trace?unit=${unit.id}`,
                                    )
                                  }
                                />
                              </OverflowMenu>
                            </TableCell>
                          );
                        }
                        return (
                          <TableCell key={cell.id}>{cell.value}</TableCell>
                        );
                      })}
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </DataTable>
      <BloodBankPager id="bb-stock-pages" paging={paging} />
      <ReasonModal
        open={!!action}
        title={modal?.title}
        label={modal?.label}
        helper={modal?.helper}
        required={modal?.required}
        danger={modal?.danger}
        primaryText={modal?.primaryText}
        onSubmit={(text) => runAction(action.name, action.unit, text)}
        onClose={() => setAction(null)}
      />
    </div>
  );
};

export default BloodBankStock;
