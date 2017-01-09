#!/usr/bin/env perl -w

# Read in list of site ids and either verify that the members can be read from the site
# or document the error.  If there is a bad user id in the site print out that user id.

use YAML qw'LoadFile';
use POSIX qw(strftime);
use File::Temp qw(tempfile);

# temporary file handle for session cookies.
our $session_fh;

# Make varible available to share common curl arguments.
our $curl_args = undef();

my $help = <<END_HELP;
 $0: accept a list of site ids from standard input and check that:
  - the site can be accessed,
  - the members can be retrieved from the site.
  The file of site ids should have one id per line.  Empty and commented lines are ignored.
  There must be a credentials file that specifies:
  - the url to use to access the Sakai instance,
  - the user name and password of an admin account.
  - the database user to use in sql queries.
  See the file
  credentials.yml.TEMPLATE for a sample file to copy and configure.

  Status code of 000 suggests a serious problem in configuration.  Check the credentials file.
  Status code of 200 the request is fine.  You probably won't see that explicitly.
  Status code of 400 is expected when the membership request can not be completed.
  Status code of 501 likely means that the site doesn't exist as requested.
END_HELP
    
# If asking for help give it.
if (defined($ARGV[0]) && $ARGV[0] =~ /^-?-h/i) {
  print $help;
  exit 1;
}

# Store the connection information externally.
sub setupCredentials {

  my ($yml_file) = shift(@ARGV) || './credentials.yml';
  my ($credentials) = LoadFile($yml_file) || die("can't read credentials file: [$yml_file]");
  $HOST=$credentials->{HOST};
  $USER=$credentials->{USER};
  $PASSWORD=$credentials->{PASSWORD};
  $DB_USER=$credentials->{DB_USER};
}

# Setup a ctools session to share with later calls.
sub setupSession {
  $session_fh = new File::Temp( UNLINK => 1 );
  # setup the common curl args here since session dependent.
  # add -i to get headers printed
  $curl_args = " -o - -c $session_fh -b $session_fh ";
  $memCmd = "curl -s -S $curl_args -X POST -F \"_username=$USER\" -F \"_password=$PASSWORD\" $HOST/direct/session";
  $result = `$memCmd`;
  return $result;
}

# Get site members from ctools via API call.
sub getMembers {
  # Assumes that setupSession has been called first.
  my $sid = shift;
  # ask for members in the site.
  $mem_cmd = "curl -sw '\\n%{http_code} %{url_effective}\\n' $curl_args $HOST/direct/site/$sid/memberships.json";
  my $result = `$mem_cmd`;
  return $result;
}

# If find that can't get list because of missing user, print the sql to delete memberships for that user.
sub handleMissingEid {
  my $eid = shift;
  return unless (length($eid) > 0);

  my $deleteMembership = "delete from ${DB_USER}.SAKAI_REALM_RL_GR where user_id in ( select user_id from ${DB_USER}.SAKAI_USER_ID_MAP where eid = '$eid'  )";
  print "\tmissing_eid: $eid\tsql:\t$deleteMembership\n";
}

# Parse the (possibly disappointing) results of the membership call.
sub parseMembers {
  my $text = shift;

  # get http status and site id
  ($status,$site) = ($text =~ /(\d\d\d)\s+http.+\/site\/(.+)\/memberships.json$/ms);
  $status |= "";
  $site |= "";
    
  # find the invalid member if it exists.
  ($eid) = ($text =~ /eid=id=(\w+)\s/ms);
  $eid |= "";

  # Print a summary for the site.
  if ($status == 200) {
    print "$site\n";
  } else {
    print "# ---- $status\t$site";
    if (length($eid) > 0) {
      print handleMissingEid($eid);
    } else {
      print "\n";
    }
  }
}

# Verify that the site ids provided can be accessed and can provide
# a list of site members.
# Input is a file of site ids to check.
sub runVerifyMembers {
  setupCredentials;
  while (<>) {
    next if (/^\s*$/);
    next if (/^\s*#/);
    my $siteId = $_;
    chomp($siteId);
    setupSession();
    my $members = getMembers($siteId);
    parseMembers $members;
  }
}

runVerifyMembers;

#end