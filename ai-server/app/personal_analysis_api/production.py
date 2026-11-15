"""Production presentation connection, deliberately empty pending policy review.

Neither develop nor MR !357 contains an approved PresentationPolicy or registry.
The candidate inventory in presentation-review.json is UNAPPROVED. Once policy
owners approve the actual wording/IDs/priorities/renderers, connect that policy
here in a reviewed change and record its approval source and presentation version.
Environment flags, version bindings and reference hashes do not grant approval.
"""

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .adapter import PresentationPolicy


def production_presentation_policy() -> "PresentationPolicy | None":
    """No approved policy is available; never fall back to the dev proposal."""
    return None
