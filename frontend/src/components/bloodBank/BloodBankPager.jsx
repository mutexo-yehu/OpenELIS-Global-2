import React, { useState } from "react";
import { Pagination } from "@carbon/react";
import { useIntl } from "react-intl";

export const PAGE_SIZES = [25, 50, 100];

/**
 * One page of a list shown on screen. Back to the first page whenever
 * resetKey changes (a new tab or filter), and never past the last page when
 * the list shrinks.
 */
export const usePage = (items, resetKey) => {
  // the page chosen applies to the list it was chosen on (its resetKey)
  const [chosen, setChosen] = useState({ page: 1, key: resetKey });
  const [pageSize, setPageSize] = useState(PAGE_SIZES[0]);
  const page = chosen.key === resetKey ? chosen.page : 1;
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const current = Math.min(page, pages);
  return {
    page: current,
    pageSize,
    total: items.length,
    shown: items.slice((current - 1) * pageSize, current * pageSize),
    onChange: ({ page: nextPage, pageSize: nextSize }) => {
      setChosen({ page: nextSize !== pageSize ? 1 : nextPage, key: resetKey });
      setPageSize(nextSize);
    },
  };
};

/** Carbon pagination under a units table: 25 per page by default. */
const BloodBankPager = ({ id, paging }) => {
  const intl = useIntl();
  return (
    <Pagination
      id={id}
      page={paging.page}
      pageSize={paging.pageSize}
      pageSizes={PAGE_SIZES}
      totalItems={paging.total}
      itemsPerPageText={intl.formatMessage({
        id: "bloodbank.pagination.itemsPerPage",
      })}
      onChange={paging.onChange}
    />
  );
};

export default BloodBankPager;
