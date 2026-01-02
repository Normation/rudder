// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
mod category;
pub use category::Category;

mod category_collection;
pub use category_collection::CategoryCollection;

mod collection;
pub use collection::Collection;
mod download_result;
pub use download_result::DownloadResult;
pub use download_result::UpdateDownloadResult;
mod info;
mod operation_result_code;
pub use operation_result_code::OperationResultCode;

mod installation_result;
pub use installation_result::InstallationResult;
pub use installation_result::UpdateInstallationResult;

pub use info::Info;
pub use info::InfoData;
